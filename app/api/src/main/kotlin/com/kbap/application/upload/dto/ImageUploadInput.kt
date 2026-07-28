package com.kbap.application.upload.dto

data class ImageUploadInput(
    val memberId: Long,
    val purpose: String,
    val contentType: String,
    val contentLength: Long,
)
