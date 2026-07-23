package com.kbap.infra.llm.food

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class FoodContentParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object FoodContentJsonParser {
    val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)

    inline fun <reified T> parse(raw: String): T {
        val json = stripCodeFence(raw)
        return try {
            objectMapper.readValue<T>(json)
        } catch (e: JacksonException) {
            throw FoodContentParseException("LLM 응답을 파싱하지 못했습니다: $raw", e)
        }
    }

    fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
