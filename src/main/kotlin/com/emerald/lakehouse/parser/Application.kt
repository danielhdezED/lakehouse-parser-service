package com.emerald.lakehouse.parser

import com.emerald.lakehouse.parser.config.AppConfig
import com.emerald.lakehouse.parser.decode.CipherKeyLoader
import com.emerald.lakehouse.parser.nats.NatsShadowConsumer
import com.emerald.lakehouse.parser.routes.healthRoute
import com.emerald.lakehouse.parser.routes.metricsRoute
import com.emerald.lakehouse.parser.routes.webhookRoute
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    // Fase 4b Etapa 3 -- consumer NATS en modo sombra, ver kdoc de NatsShadowConsumer.
    // Best-effort: si NATS no está disponible o el consumer no existe todavía (falta correr
    // setup_nats_streams.sh), el servicio sigue funcionando normal vía el webhook -- mismo
    // criterio que CipherKeyLoader/DuckLakeSync en este ecosistema.
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val natsConsumer = NatsShadowConsumer(config, cipher)
    natsConsumer.start(backgroundScope)
    monitor.subscribe(ApplicationStopping) {
        natsConsumer.stop()
        backgroundScope.cancel()
    }

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        registry = prometheusRegistry
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }

    // El healthcheck de Docker Compose pega a /health cada 5s, y Prometheus va a scrapear
    // /metrics con la misma frecuencia — sin este filtro, ambos generan una línea de log
    // por request para siempre (json-file sin rotación configurada en docker-compose.yml,
    // ver docs/ai/lakehouse-context.md).
    install(CallLogging) {
        filter { call -> call.request.path() !in setOf("/health", "/metrics") }
    }

    routing {
        healthRoute()
        metricsRoute(prometheusRegistry)
        webhookRoute(config, cipher)
    }
}
