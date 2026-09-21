package com.kbap.common.port.push

data class PushReceipt(
    val ok: Boolean,
    val errorCode: String? = null,
    val message: String? = null,
)

fun interface PushReceiptClient {
    fun fetch(ticketIds: List<String>): Map<String, PushReceipt>
}
