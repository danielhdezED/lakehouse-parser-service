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
}
