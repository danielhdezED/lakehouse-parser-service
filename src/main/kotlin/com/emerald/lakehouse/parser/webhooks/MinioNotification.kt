package com.emerald.lakehouse.parser.webhooks

import kotlinx.serialization.Serializable

/**
 * Subconjunto del payload real de notificaciones de bucket de MinIO — mismo shape que
 * `MinioNotification.kt` en lakehouse-silver-service, duplicado a propósito (cada servicio
 * es un deployable independiente, sin dependencia compartida entre sí).
 */
@Serializable
data class MinioNotification(val Records: List<MinioRecord> = emptyList())

@Serializable
data class MinioRecord(
    val eventName: String? = null,
    val s3: MinioS3Entity? = null,
)

@Serializable
data class MinioS3Entity(
    val bucket: MinioBucket? = null,
    val `object`: MinioObjectKey? = null,
)

@Serializable
data class MinioBucket(val name: String? = null)

@Serializable
data class MinioObjectKey(val key: String? = null)
