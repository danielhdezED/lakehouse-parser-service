package com.emerald.lakehouse.parser

import com.emerald.lakehouse.parser.config.AppConfig
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookRouteTest {

    @Test
    fun `ignores notifications for objects outside bronze telemetry parquet`() = testApplication {
        application { lakehouseParserModule(AppConfig.fromEnvironment()) }

        val response = client.post("/webhooks/minio") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"Records":[{"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":"bronze"},"object":{"key":"telemetry/foo.metadata.json"}}}]}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"processed\":false"))
    }

    @Test
    fun `qualifying notification is accepted and never crashes even without a live DuckLake catalog`() = testApplication {
        application { lakehouseParserModule(AppConfig.fromEnvironment()) }

        val response = client.post("/webhooks/minio") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"Records":[{"eventName":"s3:ObjectCreated:Put","s3":{"bucket":{"name":"bronze"},"object":{"key":"telemetry/project=Imbera/tec=ImberaLink/device=X/y=2026/m=07/d=13/extraction_id=e1/file.parquet"}}}]}""",
            )
        }

        // No hay Postgres/MinIO reales en el entorno de test — DuckLakeCatalog.attach falla,
        // pero el error se atrapa y de todas formas responde 200 (nunca tumba al caller).
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `qualifies real MinIO payload shape with URL-encoded key`() = testApplication {
        // Regresión: el payload real de MinIO codifica el key en URL ("/" -> "%2F",
        // "=" -> "%3D") — capturado en vivo contra el servidor (2026-07-13) interceptando
        // el webhook con un catcher HTTP temporal. Un key con "%2F" en vez de "/" literal
        // hacía que el filtro `contains("telemetry/")` nunca calificara, así que el trigger
        // automático de MinIO nunca procesaba nada (solo las pruebas manuales, con un JSON
        // armado a mano sin codificar, sí disparaban el pipeline).
        application { lakehouseParserModule(AppConfig.fromEnvironment()) }

        val response = client.post("/webhooks/minio") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"EventName":"s3:ObjectCreated:Put","Key":"bronze/telemetry%2Fproject%3DImbera%2Fdevice%3DX%2Ffile.parquet",""" +
                    """"Records":[{"eventVersion":"2.0","eventSource":"minio:s3","eventName":"s3:ObjectCreated:Put",""" +
                    """"s3":{"s3SchemaVersion":"1.0","bucket":{"name":"bronze"},""" +
                    """"object":{"key":"telemetry%2Fproject%3DImbera%2Fdevice%3DX%2Ffile.parquet","size":5}}}]}""",
            )
        }

        // Debe intentar procesar (attach falla sin Postgres real, pero eso confirma que sí
        // calificó — antes de este fix hubiera respondido `processed:false` de inmediato,
        // sin siquiera intentar el ATTACH).
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"error\""), "expected an attach error (proves it tried to process), got: ${response.bodyAsText()}")
    }
}
