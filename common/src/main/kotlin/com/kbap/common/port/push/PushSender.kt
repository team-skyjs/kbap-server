package com.kbap.common.port.push

fun interface PushSender {
    fun send(messages: List<PushMessage>): List<PushTicket>
}
