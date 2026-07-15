package com.kbap.application.upload

import java.time.Duration

data class ImageUploadProperties(
    val allowedContentTypes: Set<String>,
    val maxBytes: Long,
    val uploadTtl: Duration,
    val publicBaseUrl: String,
)
