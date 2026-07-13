FROM gradle:8.14.3-jdk17 AS build
WORKDIR /app
COPY . .
# coolector-parser se publica como paquete privado en GitHub Packages — igual que
# lakehouse-silver-service, este build necesita credenciales de solo lectura durante
# `gradle buildFatJar`. Ver README.md.
ARG GITHUB_ACTOR
# GITHUB_TOKEN se pasa como BuildKit secret (--secret id=github_token,src=...), nunca
# como ARG/ENV — esos quedan en el historial de capas de la imagen aunque solo se usen
# en este stage intermedio. Ver README.md para el comando exacto de build.
RUN --mount=type=secret,id=github_token \
    GITHUB_ACTOR="$GITHUB_ACTOR" GITHUB_TOKEN="$(cat /run/secrets/github_token)" \
    gradle buildFatJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
