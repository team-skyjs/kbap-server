package com.kbap.infra.llm.menu

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.scan.InterpretedName

class ScannedNameParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ScannedNameParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(raw: String, expectedSize: Int): List<InterpretedName> {
        val json = stripCodeFence(raw)
        val names = try {
            objectMapper.readValue<List<String?>>(json)
        } catch (e: JacksonException) {
            throw ScannedNameParseException("LLM 응답을 문자열 배열로 파싱하지 못했습니다: $raw", e)
        }
        if (names.size != expectedSize) {
            throw ScannedNameParseException("LLM 응답 개수(${names.size})가 요청 개수($expectedSize)와 다릅니다")
        }
        return names.map { toInterpreted(it) }
    }

    private fun toInterpreted(value: String?): InterpretedName {
        val name = value?.trim().orEmpty()
        val notMenuName = name.isBlank() ||
            name.equals(NOT_FOOD, ignoreCase = true) ||
            name.length > KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH
        return if (notMenuName) InterpretedName.NotFood else InterpretedName.StandardName(name)
    }

    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    companion object {
        private const val NOT_FOOD = "NOT_FOOD"
    }
}
