package com.emerald.lakehouse.parser.routes

import com.emerald.coolector.parser.utils.ImberaLinkCipher
import com.emerald.lakehouse.parser.ParserPipeline
import com.emerald.lakehouse.parser.config.AppConfig
import com.emerald.lakehouse.parser.duckdb.DuckLakeCatalog
import com.emerald.lakehouse.parser.webhooks.MinioNotification
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.sql.DriverManager
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class WebhookResponse(val processed: Boolean, val error: String? = null)

/**
 * Recibe la notificación de bucket de MinIO sobre `bronze` (`s3:ObjectCreated:*`, ver
 * `mc event add` en docs/ai/lakehouse-context.md) y decodifica **solo el objeto que el
 * evento indica** — lee el Parquet directo de MinIO (`ParserPipeline` → `BronzeObjectReader`),
 * nunca la tabla DuckLake `bronze.telemetry_readings`, por lo que no depende de que
 * `DuckLakeSync` (async, en lakehouse-ingestion-api) haya terminado de sincronizar — elimina
 * la race condition que tenía el diseño de la Fase 2.
 *
 * Siempre responde `200` (incluso si falla) para que MinIO no reintente en bucle — mismo
 * criterio best-effort que `DuckLakeSync`/`WebhookRoute` en los otros servicios.
 */
fun Route.webhookRoute(config: AppConfig, cipher: ImberaLinkCipher?) {
    val logger = LoggerFactory.getLogger("WebhookRoute")

    post("/webhooks/minio") {
        val notification = call.receive<MinioNotification>()
        val objectKey = notification.Records.firstOrNull { record ->
            val key = record.s3?.`object`?.key.orEmpty()
            record.eventName?.startsWith("s3:ObjectCreated:") == true &&
                key.contains("telemetry/") &&
                key.endsWith(".parquet")
        }?.s3?.`object`?.key

        if (objectKey == null) {
            call.respond(HttpStatusCode.OK, WebhookResponse(processed = false))
            return@post
        }

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
            logger.info("[WebhookRoute] decoded {} -> {}", objectKey, silverPath ?: "(nada que decodificar)")
            call.respond(HttpStatusCode.OK, WebhookResponse(processed = silverPath != null))
        } catch (e: Exception) {
            logger.error("[WebhookRoute] ERROR processing {}: {}", objectKey, e.message, e)
            call.respond(HttpStatusCode.OK, WebhookResponse(processed = false, error = e.message))
        }
    }
}
