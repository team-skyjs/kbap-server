package com.kbap.common.port.teamchannel

fun interface TeamChannelSender {
    fun send(text: String)
}
