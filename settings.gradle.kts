rootProject.name = "lakehouse-parser-service"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // coolector-parser (decodificación BLE compartida con Coolector-SDK) se publica
        // como paquete privado. Mismo mecanismo de credenciales que usa Coolector-SDK
        // (settings.gradle.kts) y lakehouse-silver-service. El PAT (scope read:packages)
        // se pasa vía env var GITHUB_TOKEN / GITHUB_ACTOR o gradle.properties local
        // (gpr.user/gpr.key) — nunca se commitea.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/danielhdezED/coolector-parser")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
