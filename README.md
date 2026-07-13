# lakehouse-parser-service

Servicio Kotlin + Ktor que decodifica telemetría Bronze cruda (BLE, vía `Coolector_Parser`) y
escribe el resultado ya decodificado a `silver/`. Es el equivalente local a `cool-parser` (Go)
en la arquitectura original: una Cloud Function independiente, disparada por evento, que
decodifica y **es la única pieza con acceso a la llave de descifrado**. `lakehouse-silver-service`
(otro repo) lee lo que este servicio deja en `silver/` y calcula stats/resúmenes/fallas —
nunca decodifica ni tiene acceso a la llave, mismo split que tienen `cool-parser` y
`solkos-in-process-analytics` en el sistema original.

Contexto completo en
[`docs/ai/in-process-analytics-migration-plan.md`](https://github.com/danielhdezED/Coolector-SDK/blob/main/docs/ai/in-process-analytics-migration-plan.md)
y [`docs/ai/lakehouse-context.md`](https://github.com/danielhdezED/Coolector-SDK/blob/main/docs/ai/lakehouse-context.md)
del repo KMP (`Coolector-SDK`).

## Por qué es un servicio separado de `lakehouse-silver-service`

No es una cuestión de reutilización de lógica — `Coolector_Parser` (la librería) sigue
siendo el único lugar con lógica de parseo, reutilizada sin cambios acá y en
`lakehouse-silver-service`. Es aislamiento real: solo este servicio necesita/tiene acceso a
la llave de cifrado; si el contenedor de analytics se compromete, nunca tuvo acceso a ella.

## Disparo: notificación de bucket de MinIO sobre `bronze`

`POST /webhooks/minio` recibe el evento `s3:ObjectCreated:*` de MinIO sobre el bucket
`bronze` (mismo mecanismo que usaba `lakehouse-silver-service` en la versión anterior, ahora
movido acá). **Lee directo el objeto Parquet que el evento indica**
(`read_parquet('s3://bronze/<key>')`), no la tabla DuckLake `bronze.telemetry_readings` —
por diseño, para no depender de que `DuckLakeSync` (async, en `lakehouse-ingestion-api`) haya
terminado de sincronizar. Esto es lo que elimina la race condition que tenía el diseño
anterior (el webhook podía llegar antes de que `DuckLakeSync` completara).

Al terminar, escribe un Parquet real en `silver/telemetry/technology=.../device_id=.../
year=.../month=.../day=.../` y registra la tabla `silver.telemetry_decoded` en el catálogo
DuckLake — eso, a su vez, dispara la notificación de MinIO sobre `silver` que activa
`lakehouse-silver-service`.

## Endpoints

- `GET /health` — liveness check.
- `POST /webhooks/minio` — notificación de bucket de MinIO sobre `bronze`. Siempre responde
  `200` (incluso si el pipeline falla) para que MinIO no reintente en bucle.

## Llave de descifrado (`ImberaLinkCipher`)

Ver `docs/ai/security-guidelines.md` en Coolector-SDK. Resumen:

- Es material con licencia (`CoolectorSDKLicense`/`CoreSecrets` en el SDK) — este repo
  **nunca** la genera, adivina ni embebe en código.
- Se monta de solo lectura desde fuera del repo (`PARSER_CIPHER_KEY_PATH`, default
  `/etc/lakehouse-mtls/parser-cipher-key`), mismo patrón que la llave de la Intermediate CA
  de mTLS en `lakehouse-ingestion-api`.
- Best-effort: si el archivo no existe, el servicio sigue funcionando decodificando solo
  firmwares sin cifrar (v126) — no tumba el arranque (`CipherKeyLoader.kt`).

## Configuración (variables de entorno)

| Variable | Default |
|---|---|
| `MINIO_ENDPOINT` | `minio:9000` |
| `MINIO_ACCESS_KEY` | `minioadmin` |
| `MINIO_SECRET_KEY` | `minioadmin` |
| `BRONZE_BUCKET_NAME` | `bronze` |
| `SILVER_BUCKET_NAME` | `silver` |
| `PORT` | `8082` |
| `DUCKLAKE_CATALOG_HOST` | `postgres` |
| `DUCKLAKE_CATALOG_PORT` | `5432` |
| `DUCKLAKE_CATALOG_DB` | `ducklake_catalog` |
| `DUCKLAKE_CATALOG_USER` | `ducklake` |
| `DUCKLAKE_CATALOG_PASSWORD` | `ducklake` |
| `DUCKLAKE_DATA_PATH` | `s3://ducklake-catalog/` |
| `PARSER_CIPHER_KEY_PATH` | `/etc/lakehouse-mtls/parser-cipher-key` |

## Dependencia privada: `coolector-parser`

Igual que `lakehouse-silver-service`: necesita un PAT de solo lectura (`read:packages`)
para resolver `com.emerald.coolector:coolector-parser:1.2.1` desde GitHub Packages.

```bash
export GITHUB_ACTOR=<tu usuario de GitHub>
export GITHUB_TOKEN=<el PAT>
```

Para Docker, como BuildKit secret (nunca `--build-arg`):
```bash
DOCKER_BUILDKIT=1 docker build \
  --build-arg GITHUB_ACTOR=<tu usuario> \
  --secret id=github_token,env=GITHUB_TOKEN \
  -t lakehouse-parser-service .
```

## Correr local

```bash
./gradlew run
```

## Tests

```bash
./gradlew test
```

## Build

```bash
./gradlew buildFatJar   # genera build/libs/lakehouse-parser-service-all.jar
```

## Despliegue

Mismo patrón que `lakehouse-silver-service`: clonado como sibling en el servidor
(`/home/daniel/lakehouse-parser-service`) vía deploy key SSH de solo lectura dedicada.

Bloque en `devops-lakehouse-local/docker-compose.yml`:

```yaml
  parser-service:
    build:
      context: ../lakehouse-parser-service
      secrets:
        - github_token
      args:
        GITHUB_ACTOR: danielhdezED
    container_name: lakehouse-parser-service
    expose:
      - "8082"
    volumes:
      # Llave de descifrado — montada de solo lectura desde fuera del repo, nunca
      # commiteada. Ver "Llave de descifrado" arriba.
      - /home/daniel/lakehouse-mtls/parser-cipher-key:/etc/lakehouse-mtls/parser-cipher-key:ro
    environment:
      MINIO_ENDPOINT: minio:9000
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
      BRONZE_BUCKET_NAME: bronze
      SILVER_BUCKET_NAME: silver
      PORT: "8082"
      DUCKLAKE_CATALOG_HOST: postgres
      DUCKLAKE_CATALOG_PORT: "5432"
      DUCKLAKE_CATALOG_DB: ducklake_catalog
      DUCKLAKE_CATALOG_USER: ducklake
      DUCKLAKE_CATALOG_PASSWORD: ducklake
      DUCKLAKE_DATA_PATH: s3://ducklake-catalog/
    depends_on:
      - minio
      - postgres
```

**Configurar la notificación de MinIO** (mueve la suscripción de `bronze` que antes apuntaba
a `silver-service`, hacia acá):
```bash
mc admin config set local/ notify_webhook:parser endpoint='http://parser-service:8082/webhooks/minio'
docker compose restart minio
mc event remove local/bronze arn:minio:sqs::silver:webhook --event put
mc event add local/bronze arn:minio:sqs::parser:webhook --event put --suffix .parquet
```
