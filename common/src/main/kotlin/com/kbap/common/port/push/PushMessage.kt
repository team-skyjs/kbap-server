package com.kbap.common.port.push

data class PushMessage(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any>,
    val channelId: String = "default",
    val ttlSeconds: Int? = null,
)
