package com.kbap.api.image

data class ImageUploadInput(
    val memberId: Long,
    val purpose: String,
    val contentType: String,
    val contentLength: Long,
)
