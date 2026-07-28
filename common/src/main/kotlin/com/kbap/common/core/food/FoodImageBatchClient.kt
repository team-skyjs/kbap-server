package com.kbap.common.core.food

interface FoodImageBatchClient {
    fun submit(entries: List<Entry>): String

    fun status(openaiBatchId: String): BatchPoll

    fun streamResults(fileId: String, onItem: (Result) -> Unit)

    data class Entry(val customId: String, val prompt: String)

    data class BatchPoll(val state: State, val outputFileId: String?, val errorFileId: String?)

    enum class State { IN_PROGRESS, COMPLETED, FAILED, EXPIRED }

    class Result(
        val customId: String,
        val bytes: ByteArray?,
        val errorMessage: String?,
        val usage: Usage?,
    )

    data class Usage(val inputTokens: Long, val outputTokens: Long)
}
