package com.emerald.lakehouse.parser.routes

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

// Scrapeado por Prometheus dentro del cluster (ServiceMonitor -> ClusterIP), nunca expuesto
// a través de nginx/mTLS -- ver docs/ai/kubernetes-elastic-infrastructure-plan.md (Coolector-SDK).
fun Route.metricsRoute(registry: PrometheusMeterRegistry) {
    get("/metrics") {
        call.respondText(registry.scrape())
    }
}
