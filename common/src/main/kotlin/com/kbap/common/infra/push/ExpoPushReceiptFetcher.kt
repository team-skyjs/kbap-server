package com.kbap.common.infra.push

import com.kbap.common.port.push.PushReceipt
import com.kbap.common.port.push.PushReceiptFetcher
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

internal data class ExpoReceipt(
    val status: String = "",
    val message: String? = null,
    val details: Map<String, Any>? = null,
) {
    fun toReceipt(): PushReceipt =
        if (status == "ok") PushReceipt(ok = true) else PushReceipt(ok = false, errorCode = details?.get("error") as? String, message = message)
}

internal data class ExpoReceiptsResponse(
    val data: Map<String, ExpoReceipt> = emptyMap(),
)

class ExpoPushReceiptFetcher internal constructor(
    private val restClient: RestClient,
) : PushReceiptFetcher {
    override fun fetch(ticketIds: List<String>): Map<String, PushReceipt> =
        ticketIds.chunked(MAX_IDS_PER_REQUEST).flatMap { ids -> post(ids).entries }.associate { it.key to it.value.toReceipt() }

    private fun post(ids: List<String>): Map<String, ExpoReceipt> =
        restClient.post()
            .uri(RECEIPTS_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(mapOf("ids" to ids))
            .retrieve()
            .body(ExpoReceiptsResponse::class.java)
            ?.data
            .orEmpty()

    companion object {
        private const val MAX_IDS_PER_REQUEST = 1000
        private const val RECEIPTS_PATH = "/--/api/v2/push/getReceipts"

        fun create(baseUrl: String, accessToken: String): ExpoPushReceiptFetcher = create(baseUrl, accessToken, expoRestClientBuilder())

        internal fun create(baseUrl: String, accessToken: String, builder: RestClient.Builder): ExpoPushReceiptFetcher =
            ExpoPushReceiptFetcher(expoRestClient(baseUrl, accessToken, builder))
    }
}
