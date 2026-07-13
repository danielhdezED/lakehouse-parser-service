package com.emerald.lakehouse.parser.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val timestamp: String,
)

fun Route.healthRoute() {
    get("/health") {
        call.respond(
            HttpStatusCode.OK,
            HealthResponse(
                status = "ok",
                service = "coolector-lakehouse-parser-service",
                timestamp = Instant.now().toString(),
            ),
        )
    }
}
