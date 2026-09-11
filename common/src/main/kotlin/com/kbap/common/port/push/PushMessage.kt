package com.kbap.common.port.push

data class PushMessage(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any>,
)
