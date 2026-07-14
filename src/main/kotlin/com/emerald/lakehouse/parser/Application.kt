package com.emerald.lakehouse.parser

import com.emerald.lakehouse.parser.config.AppConfig
import com.emerald.lakehouse.parser.decode.CipherKeyLoader
import com.emerald.lakehouse.parser.routes.healthRoute
import com.emerald.lakehouse.parser.routes.webhookRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, module = { lakehouseParserModule(config) })
        .start(wait = true)
}

fun Application.lakehouseParserModule(config: AppConfig) {
    // Se carga una sola vez al arrancar, no por request — ver kdoc de CipherKeyLoader sobre
    // por qué es best-effort (null si el archivo no está montado todavía).
    val cipher = CipherKeyLoader.load(config.parserCipherKeyPath, config.parserImberaBlobPath)

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    install(CallLogging)

    routing {
        healthRoute()
        webhookRoute(config, cipher)
    }
}
