package com.kbap.api.admin

data class FieldError(
    val field: String,
    val code: String,
    val message: String,
)
