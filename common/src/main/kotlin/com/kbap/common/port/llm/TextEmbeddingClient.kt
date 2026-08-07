package com.kbap.common.port.llm

fun interface TextEmbeddingClient {
    fun embed(texts: List<String>): List<FloatArray>
}
