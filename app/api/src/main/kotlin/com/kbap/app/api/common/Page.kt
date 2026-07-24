package com.kbap.app.api.common

data class Page<T>(
    val items: List<T>,
    val hasNext: Boolean,
    val nextCursor: Long? = null,
)
