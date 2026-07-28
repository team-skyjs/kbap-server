package com.kbap.api.upload

data class ImageUploadInput(
    val memberId: Long,
    val purpose: String,
    val contentType: String,
    val contentLength: Long,
)
