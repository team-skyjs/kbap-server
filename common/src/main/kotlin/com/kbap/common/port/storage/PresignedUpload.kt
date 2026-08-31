package com.kbap.common.port.storage

import java.time.Instant

data class PresignedUpload(
    val uploadUrl: String,
    val requiredHeaders: Map<String, String>,
    val publicUrl: String,
    val objectKey: String,
    val expiresAt: Instant,
)
