package com.kbap.common.port.push

fun interface PushClient {
    fun send(messages: List<PushMessage>): List<PushTicket>
}
