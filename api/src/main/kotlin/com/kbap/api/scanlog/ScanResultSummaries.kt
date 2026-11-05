package com.kbap.api.scanlog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.processedfood.ProcessedFoodLabelScanResponse
import com.kbap.api.processedfood.ProcessedFoodNameScanResponse
import com.kbap.api.processedfood.ProcessedFoodNameScanState
import com.kbap.api.processedfood.ProcessedFoodSummaryResponse
import com.kbap.api.processedfood.ProcessedFoodVisionScanResponse
import com.kbap.api.scan.ScanResult
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.llm.ProcessedFoodPhotoKind

object ScanResultSummaries {
    const val MAX_SUMMARY_CHARS = 16_000
    private const val MAX_NAME_CHARS = 100
    private const val MAX_LIST_SIZE = 100
    private val TRUNCATED_LIST_SIZES = listOf(30, 10, 3)
    private const val MAX_ERROR_CODE_CHARS = 32
    private val RETAKE_CODES = setOf(ErrorCode.MENU_BOARD_NOT_DETECTED, ErrorCode.PROCESSED_FOOD_LABEL_NOT_READ)
    private val mapper = jacksonObjectMapper()

    fun empty(): ScanResultLogEntry = ScanResultLogEntry(ScanOutcome.EMPTY, ScanUiKind.EMPTY)

    fun failure(error: Throwable): ScanResultLogEntry {
        val code = (error as? BusinessException)?.errorCode
        val retake = code in RETAKE_CODES
        return ScanResultLogEntry(
            outcome = if (retake) ScanOutcome.RETAKE else ScanOutcome.ERROR,
            uiKind = if (retake) ScanUiKind.RETAKE else ScanUiKind.ERROR,
            errorCode = (code?.code ?: error.javaClass.simpleName).take(MAX_ERROR_CODE_CHARS),
        )
    }

    fun menu(result: ScanResult): ScanResultLogEntry {
        val (items, photoOnly) = result.items.partition { it.idx != null }
        val found = result.items.isNotEmpty()
        return ScanResultLogEntry(
            outcome = if (found) ScanOutcome.SUCCESS else ScanOutcome.EMPTY,
            uiKind = if (found) ScanUiKind.MENU_LIST else ScanUiKind.EMPTY,
            summary = mapOf(
                "degraded" to result.degraded,
                "items" to items.take(MAX_LIST_SIZE).map(::menuItem),
                "itemCount" to items.size,
                "photoOnly" to photoOnly.take(MAX_LIST_SIZE).map(::menuItem),
                "photoOnlyCount" to photoOnly.size,
            ),
            foodLinks = result.items.mapNotNull { item ->
                item.foodId?.let { ScanResultFoodLink(it, item.matched, clip(item.originalScannedName ?: item.koreanName ?: item.name)) }
            }.distinctBy { it.foodId },
        )
    }

    fun nameScan(response: ProcessedFoodNameScanResponse): ScanResultLogEntry =
        byState(response.state, summary = mapOf("state" to response.state.name, "candidates" to candidates(response.candidates)))

    fun label(response: ProcessedFoodLabelScanResponse): ScanResultLogEntry =
        byState(response.state, summary = labelSummary(response))

    fun vision(response: ProcessedFoodVisionScanResponse): ScanResultLogEntry {
        val summary = mapOf(
            "kind" to response.kind.name,
            "confidence" to response.confidence,
            "reason" to response.reason?.name,
            "state" to response.state.name,
            "front" to response.front?.let {
                mapOf("productName" to clip(it.productName), "maker" to clip(it.maker), "reportNo" to it.reportNo, "candidates" to candidates(it.candidates))
            },
            "label" to response.label?.let(::labelSummary),
        )
        val base = when (response.kind) {
            ProcessedFoodPhotoKind.OTHER -> ScanResultLogEntry(ScanOutcome.RETAKE, ScanUiKind.RETAKE)
            ProcessedFoodPhotoKind.MENU -> ScanResultLogEntry(ScanOutcome.SUCCESS, ScanUiKind.NOT_PACKAGED)
            else -> byState(response.state)
        }
        return base.copy(visionKind = response.kind.name, summary = summary)
    }

    fun toJson(summary: Map<String, Any?>): String {
        val json = mapper.writeValueAsString(summary)
        if (json.length <= MAX_SUMMARY_CHARS) return json
        val marker = mapOf("truncated" to true, "originalChars" to json.length)
        TRUNCATED_LIST_SIZES.forEach { size ->
            val shortened = mapper.writeValueAsString(summary.mapValues { (_, value) -> if (value is List<*>) value.take(size) else value } + marker)
            if (shortened.length <= MAX_SUMMARY_CHARS) return shortened
        }
        return mapper.writeValueAsString(summary.filterValues { it !is Collection<*> } + marker)
    }

    fun fromJson(json: String?): Map<String, Any?>? = json?.let { mapper.readValue(it, Map::class.java) }?.mapKeys { it.key.toString() }

    private fun byState(state: ProcessedFoodNameScanState, summary: Map<String, Any?>? = null): ScanResultLogEntry {
        val (outcome, uiKind) = when (state) {
            ProcessedFoodNameScanState.NO_CANDIDATE -> ScanOutcome.EMPTY to ScanUiKind.PFOOD_NO_CANDIDATE
            ProcessedFoodNameScanState.LABEL_RESULT -> ScanOutcome.SUCCESS to ScanUiKind.LABEL_RESULT
            ProcessedFoodNameScanState.NOT_PACKAGED -> ScanOutcome.SUCCESS to ScanUiKind.NOT_PACKAGED
            else -> ScanOutcome.SUCCESS to ScanUiKind.PFOOD_CANDIDATES
        }
        return ScanResultLogEntry(outcome, uiKind, summary = summary)
    }

    private fun menuItem(item: ScanResult.ItemRiskResult): Map<String, Any?> = mapOf(
        "name" to clip(item.originalScannedName ?: item.koreanName ?: item.name),
        "matched" to item.matched,
        "foodId" to item.foodId.takeIf { item.matched },
        "riskLevel" to item.riskLevel,
        "substituted" to item.substituted,
    )

    private fun candidates(candidates: List<ProcessedFoodSummaryResponse>): List<Map<String, Any?>> =
        candidates.take(MAX_LIST_SIZE).map { mapOf("id" to it.id, "reportNo" to it.reportNo, "productName" to clip(it.productName)) }

    private fun labelSummary(response: ProcessedFoodLabelScanResponse): Map<String, Any?> = mapOf(
        "state" to response.state.name,
        "labelSource" to response.labelSource.name,
        "verdict" to response.verdict?.name,
        "detections" to response.detections.size,
        "unmapped" to response.unmapped.take(MAX_LIST_SIZE).map(::clip),
    )

    private fun clip(value: String?): String? = value?.take(MAX_NAME_CHARS)
}
