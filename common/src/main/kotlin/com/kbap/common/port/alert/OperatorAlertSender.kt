package com.kbap.common.port.alert

fun interface OperatorAlertSender {
    fun send(text: String)
}
