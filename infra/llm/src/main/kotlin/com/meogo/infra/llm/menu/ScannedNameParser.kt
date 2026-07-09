package com.meogo.infra.llm.menu

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.meogo.core.kernel.menu.KoreanMenuNameNormalizer
import com.meogo.core.kernel.scan.InterpretedName

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
        val trimmed = value?.trim().orEmpty()
        return if (isNotMenuName(trimmed)) {
            InterpretedName.NotFood
        } else {
            InterpretedName.StandardName(trimmed)
        }
    }

    private fun isNotMenuName(trimmed: String): Boolean {
        if (trimmed.isBlank()) return true
        if (trimmed.equals(NOT_FOOD, ignoreCase = true)) return true
        return trimmed.length > KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH
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
