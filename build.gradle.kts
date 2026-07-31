plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "com.emerald.lakehouse"
version = "0.1.0"

application {
    mainClass.set("com.emerald.lakehouse.parser.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.minio)

    // Conexión DuckDB embebida vía JDBC — lee el objeto Bronze directo de MinIO
    // (read_parquet('s3://bronze/...')) y escribe/registra el resultado en silver/.
    implementation(libs.duckdb.jdbc)

    // Único servicio con acceso a la llave de cifrado — ver docs/ai/security-guidelines.md
    // en Coolector-SDK y el kdoc de config/AppConfig.kt.
    implementation(libs.coolector.parser)

    // Fase 4b Etapa 3 -- consumer NATS en modo sombra, ver nats/NatsShadowConsumer.kt.
    implementation(libs.jnats)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
