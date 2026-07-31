package com.emerald.lakehouse.parser.nats

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import com.emerald.lakehouse.parser.ParserPipeline
import com.emerald.lakehouse.parser.config.AppConfig
import com.emerald.lakehouse.parser.duckdb.DuckLakeCatalog
import com.emerald.lakehouse.parser.webhooks.MinioNotification
import io.nats.client.Connection
import io.nats.client.JetStreamSubscription
import io.nats.client.Nats
import io.nats.client.PullSubscribeOptions
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Fase 4b Etapa 3 (kubernetes-elastic-infrastructure-plan.md §6, repo devops-lakehouse-local) —
 * consumer NATS en **modo sombra**: corre exactamente el mismo [ParserPipeline.run] que ya
 * dispara [com.emerald.lakehouse.parser.routes.webhookRoute], en paralelo al webhook actual
 * (que sigue siendo el único camino "oficial" mientras dure esta etapa).
 *
 * **Decisión de diseño, distinta del diseño original en papel:** el diseño original
 * (kubernetes-elastic-infrastructure-plan.md §6, punto 4) proponía un modo sombra que
 * "procesa y compara, sin escribir a Gold dos veces". Aquí se optó por dejar que el pipeline
 * escriba de verdad, apoyándose en que [ParserPipeline.run]/`SilverWriter` ya son
 * **idempotentes por diseño** (nombre de archivo determinístico + `DELETE` explícito antes del
 * `INSERT` en `silver.telemetry_decoded`, ver `SilverWriterTest` — bug real de duplicación ya
 * corregido y cubierto por tests de regresión). Una segunda escritura del mismo dato
 * simplemente reemplaza a la primera, nunca acumula — así que correr el pipeline dos veces
 * (webhook + NATS) es seguro sin necesitar lógica de comparación nueva que podría tener sus
 * propios bugs. Esto es, de hecho, una validación más fuerte que un modo sombra de solo
 * lectura: prueba que el camino de escritura real tolera disparos duplicados/concurrentes.
 *
 * A diferencia del webhook (que siempre responde `200` sin importar el resultado), este
 * consumer usa `ack`/`nak` reales — un fallo de verdad se reintenta (hasta `max-deliver=5`,
 * configurado al crear el consumer, ver `scripts/setup_nats_streams.sh` en
 * devops-lakehouse-local), algo que el webhook nunca tuvo.
 */
class NatsShadowConsumer(
    private val config: AppConfig,
    private val cipher: ImberaLinkCipher?,
) {
    private val logger = LoggerFactory.getLogger("NatsShadowConsumer")
    private val json = Json { ignoreUnknownKeys = true }

    private var connection: Connection? = null

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val conn = try {
                Nats.connect(config.natsUrl)
            } catch (e: Exception) {
                logger.error("[NatsShadowConsumer] no se pudo conectar a {}: {} -- consumer deshabilitado esta corrida", config.natsUrl, e.message, e)
                return@launch
            }
            connection = conn
            val sub = try {
                bindConsumer(conn)
            } catch (e: Exception) {
                logger.error("[NatsShadowConsumer] no se pudo bindear al consumer parser-consumer: {} -- ¿corriste setup_nats_streams.sh?", e.message, e)
                return@launch
            }
            logger.info("[NatsShadowConsumer] conectado a {} -- escuchando BRONZE_EVENTS/parser-consumer", config.natsUrl)

            while (isActive) {
                val messages = try {
                    sub.fetch(10, Duration.ofSeconds(5))
                } catch (e: Exception) {
                    logger.error("[NatsShadowConsumer] error en fetch: {}", e.message, e)
                    continue
                }
                for (msg in messages) {
                    processMessage(msg.data)
                        .let { ok -> if (ok) msg.ack() else msg.nak() }
                }
            }
        }
    }

    fun stop() {
        connection?.close()
    }

    private fun bindConsumer(conn: Connection): JetStreamSubscription {
        val js = conn.jetStream()
        val options = PullSubscribeOptions.bind("BRONZE_EVENTS", "parser-consumer")
        // Con bind() no se pasa subject -- el consumer ya existente (creado por
        // setup_nats_streams.sh, sin --filter explícito) define su propio alcance. Pasar
        // un subject aquí lo valida contra el filtro configurado del consumer y falla si no
        // coincide exactamente (bug real encontrado en el primer despliegue: "[SUB-90011]
        // Subject does not match consumer configuration filter").
        return js.subscribe(null, options)
    }

    /** Devuelve `true` si se procesó (o se descartó de forma esperada) -- `false` si hay que reintentar. */
    private suspend fun processMessage(payload: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val notification = try {
            json.decodeFromString(MinioNotification.serializer(), String(payload, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            logger.error("[NatsShadowConsumer] payload no es un MinioNotification válido: {}", e.message, e)
            return@withContext true // descartar, reintentar no ayudaría con un payload corrupto
        }

        val objectKey = notification.Records.firstOrNull { record ->
            val key = URLDecoder.decode(record.s3?.`object`?.key.orEmpty(), StandardCharsets.UTF_8)
            record.eventName?.startsWith("s3:ObjectCreated:") == true &&
                key.contains("telemetry/") &&
                key.endsWith(".parquet")
        }?.s3?.`object`?.key?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
            ?: return@withContext true // no es un evento que nos interese, no reintentar

        MDC.put("correlationId", extractExtractionId(objectKey) ?: objectKey)
        try {
            Class.forName("org.duckdb.DuckDBDriver")
            val silverPath = DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                DuckLakeCatalog.attach(conn, config)
                ParserPipeline.run(
                    conn,
                    bronzeObjectPath = "s3://${config.bronzeBucketName}/$objectKey",
                    silverRoot = "s3://${config.silverBucketName}",
                    cipher = cipher,
                )
            }
            logger.info("[NatsShadowConsumer] decoded {} -> {}", objectKey, silverPath ?: "(nada que decodificar)")
            true
        } catch (e: Exception) {
            logger.error("[NatsShadowConsumer] ERROR processing {}: {}", objectKey, e.message, e)
            false
        } finally {
            MDC.remove("correlationId")
        }
    }

    private fun extractExtractionId(objectKey: String): String? =
        Regex("extraction_id=([^/]+)/").find(objectKey)?.groupValues?.get(1)
}
