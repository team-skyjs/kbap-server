package com.kbap.api.core

data class Page<T>(
    val items: List<T>,
    val hasNext: Boolean,
    val nextCursor: Long? = null,
)
