package com.emerald.lakehouse.parser

import com.emerald.lakehouse.parser.config.AppConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthRouteTest {

    @Test
    fun `health endpoint reports ok`() = testApplication {
        application { lakehouseParserModule(AppConfig.fromEnvironment()) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }
}
