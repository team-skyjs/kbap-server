package com.kbap.batch.notification

import com.kbap.common.port.push.PushReceipt
import com.kbap.common.port.push.PushReceiptClient
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class FakePushReceiptClient : PushReceiptClient {
    val requested: MutableList<String> = mutableListOf()
    var receiptFor: (String) -> PushReceipt? = { null }
    var failWith: RuntimeException? = null

    override fun fetch(ticketIds: List<String>): Map<String, PushReceipt> {
        failWith?.let { throw it }
        requested += ticketIds
        return ticketIds.mapNotNull { id -> receiptFor(id)?.let { id to it } }.toMap()
    }

    fun reset() {
        requested.clear()
        receiptFor = { null }
        failWith = null
    }
}

@TestConfiguration
class FakePushReceiptClientConfig {
    @Bean
    @Primary
    fun fakePushReceiptClient(): FakePushReceiptClient = FakePushReceiptClient()
}
