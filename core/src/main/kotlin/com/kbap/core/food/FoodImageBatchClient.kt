package com.kbap.core.food

// OpenAI Batch API seam(KB-226) — 제출·상태조회·결과 스트리밍만 노출하고 HTTP/직렬화는 어댑터가 가둔다.
interface FoodImageBatchClient {
    // JSONL 조립 → Files 업로드 → Batch 생성까지 수행하고 openai_batch_id 를 반환한다.
    fun submit(entries: List<Entry>): String

    fun status(openaiBatchId: String): BatchPoll

    // 결과 파일을 줄 단위 스트리밍으로 읽어 항목마다 콜백한다 — 전체 파일을 메모리에 올리지 않는다.
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
