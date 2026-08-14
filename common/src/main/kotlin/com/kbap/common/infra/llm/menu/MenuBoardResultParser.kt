package com.kbap.common.infra.llm.menu

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.port.llm.ExtractedMenu

class MenuBoardParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MenuBoardResultParser {
    private val objectMapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun parse(raw: String): List<ExtractedMenu> {
        val json = stripCodeFence(raw)
        val items = try {
            if (json.startsWith("[")) {
                objectMapper.readValue<List<MenuItem>>(json)
            } else {
                objectMapper.readValue<ResultEnvelope>(json).results
                    ?: throw MenuBoardParseException("메뉴판 인식 응답에 results 가 없습니다: $raw")
            }
        } catch (e: JacksonException) {
            throw MenuBoardParseException("메뉴판 인식 응답을 파싱하지 못했습니다: $raw", e)
        }
        return items.mapNotNull { it.toExtractedMenuOrNull() }
    }

    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private data class ResultEnvelope(val results: List<MenuItem>? = null)

    private data class MenuItem(
        val name: String? = null,
        val koreanName: String? = null,
        val price: Int? = null,
        val matchedIdx: Int? = null,
    ) {
        fun toExtractedMenuOrNull(): ExtractedMenu? {
            val trimmedName = name?.trim().orEmpty()
            val trimmedKorean = koreanName?.trim()?.ifBlank { null } ?: trimmedName
            if (trimmedName.isBlank() || trimmedKorean.isBlank()) return null
            return ExtractedMenu(
                name = trimmedName,
                koreanName = trimmedKorean,
                priceKrw = price?.takeIf { it > 0 },
                matchedIdx = matchedIdx,
            )
        }
    }
}
