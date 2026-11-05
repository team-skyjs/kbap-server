package com.kbap.common.infra.llm.menu

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardLine
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import com.kbap.common.port.llm.ProcessedFoodPhotoReason

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

    fun parseLines(raw: String): List<MenuBoardLine> {
        val json = stripCodeFence(raw)
        val items = try {
            if (json.startsWith("[")) {
                objectMapper.readValue<List<LineItem>>(json)
            } else {
                objectMapper.readValue<LineEnvelope>(json).results
                    ?: throw MenuBoardParseException("메뉴판 행 응답에 results 가 없습니다: $raw")
            }
        } catch (e: JacksonException) {
            throw MenuBoardParseException("메뉴판 행 응답을 파싱하지 못했습니다: $raw", e)
        }
        return items.mapNotNull { it.toLineOrNull() }
    }

    fun parsePhoto(raw: String): ProcessedFoodPhotoReading {
        val photo = try {
            objectMapper.readValue<PhotoItem>(stripCodeFence(raw))
        } catch (e: JacksonException) {
            throw MenuBoardParseException("가공식품 사진 응답을 파싱하지 못했습니다: $raw", e)
        }
        val kind = ProcessedFoodPhotoKind.entries.firstOrNull { it.name.equals(photo.kind?.trim(), ignoreCase = true) }
            ?: throw MenuBoardParseException("가공식품 사진 응답의 kind 를 알 수 없습니다: $raw")
        val front = kind == ProcessedFoodPhotoKind.PACKAGE_FRONT
        return ProcessedFoodPhotoReading(
            kind = kind,
            confidence = (photo.confidence ?: 0.0).coerceIn(0.0, 1.0),
            reason = ProcessedFoodPhotoReason.entries.filterNot { it == ProcessedFoodPhotoReason.UNKNOWN }.firstOrNull { it.name.equals(photo.reason?.trim(), ignoreCase = true) },
            productName = photo.productName?.trim()?.ifBlank { null }?.takeIf { front },
            maker = photo.maker?.trim()?.ifBlank { null }?.takeIf { front },
            reportNo = photo.reportNo?.filter(Char::isDigit)?.takeIf { front && it.length in REPORT_NO_LENGTHS },
            labelText = photo.labelText?.trim()?.ifBlank { null }?.takeIf { kind == ProcessedFoodPhotoKind.INGREDIENT_LABEL },
            warnings = photo.warnings.orEmpty().mapNotNull { it?.trim()?.ifBlank { null } }.take(MAX_WARNINGS),
        )
    }

    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private data class PhotoItem(
        val kind: String? = null,
        val confidence: Double? = null,
        val reason: String? = null,
        val productName: String? = null,
        val maker: String? = null,
        val reportNo: String? = null,
        val labelText: String? = null,
        val warnings: List<String?>? = null,
    )

    private data class ResultEnvelope(val results: List<MenuItem>? = null)

    private data class LineEnvelope(val results: List<LineItem>? = null)

    private data class LineItem(
        val rawName: String? = null,
        val koreanName: String? = null,
        val price: Int? = null,
        val option: String? = null,
    ) {
        fun toLineOrNull(): MenuBoardLine? {
            val raw = rawName?.trim().orEmpty().ifBlank { return null }
            return MenuBoardLine(raw, koreanName?.trim()?.ifBlank { null } ?: raw, price?.takeIf { it > 0 }, option?.trim()?.ifBlank { null })
        }
    }

    private data class MenuItem(
        val name: String? = null,
        val koreanName: String? = null,
        val price: Int? = null,
        val matchedIdx: Int? = null,
        val optionLabel: String? = null,
        val option: String? = null,
        val baseName: String? = null,
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
                optionLabel = (optionLabel ?: option)?.trim()?.ifBlank { null }?.take(MAX_OPTION_LABEL_LENGTH),
                baseName = baseName?.trim()?.ifBlank { null }?.take(MAX_BASE_NAME_LENGTH),
            )
        }
    }

    companion object {
        const val MAX_OPTION_LABEL_LENGTH = 30
        const val MAX_BASE_NAME_LENGTH = 40
        private val REPORT_NO_LENGTHS = 10..16
        private const val MAX_WARNINGS = 5
    }
}
