package com.kbap.api.processedfood

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Schema(description = "가공식품 이름 스캔 요청 — 제품 앞면을 기기에서 OCR 한 텍스트")
data class ProcessedFoodNameScanRequest(
    @field:NotBlank(message = "ocrText 는 필수입니다")
    @field:Size(max = 2000, message = "ocrText 는 2000자 이하여야 합니다")
    @field:Schema(description = "앞면 OCR 텍스트(줄바꿈 포함 그대로, 2000자 이하)", example = "NEW\n농심\n새우깡\n90g", requiredMode = Schema.RequiredMode.REQUIRED)
    val ocrText: String? = null,
    @field:Schema(description = "앱 언어 코드(분석 기록용). 미지원·생략은 en", example = "ko")
    val lang: String? = null,
)

@Schema(description = "가공식품 이름 스캔 확정 요청")
data class ProcessedFoodNameConfirmRequest(
    @field:NotBlank(message = "analysisId 는 필수입니다")
    @field:Schema(description = "이름 스캔이 준 분석 ID", example = "2f1c0f7e-8a3b-4c2d-9e1f-0a1b2c3d4e5f", requiredMode = Schema.RequiredMode.REQUIRED)
    val analysisId: String? = null,
    @field:Schema(description = "고른 품목보고번호 — 후보 밖 제품도 허용. null 이면 '해당 없음'(REJECTED, 성분표 촬영으로 이어감)", example = "19700001001", nullable = true)
    val reportNo: String? = null,
)

@Schema(description = "분석 상태 — CANDIDATES: 후보 있음(확정 대기) · NO_CANDIDATE: 후보 없음(성분표 촬영으로 이어감) · CONFIRMED: 품목 확정(1회 차감) · REJECTED: 해당 없음(성분표 촬영으로 이어감) · LABEL_RESULT: 성분표 판독 결과(scan/label)")
enum class ProcessedFoodNameScanState { CANDIDATES, NO_CANDIDATE, CONFIRMED, REJECTED, LABEL_RESULT }

@Schema(description = "가공식품 이름 스캔 결과")
data class ProcessedFoodNameScanResponse(
    @field:Schema(description = "분석 ID — 확정과 성분표 이어가기(KB-560)에 그대로 쓴다")
    val analysisId: String,
    val state: ProcessedFoodNameScanState,
    @field:Schema(description = "후보(최대 5) — 검색 결과 항목과 같은 형태. NO_CANDIDATE 면 빈 배열")
    val candidates: List<ProcessedFoodSummaryResponse>,
    @field:Schema(description = "확정 가능 기한(발급 후 30분)")
    val expiresAt: LocalDateTime,
)

@Schema(description = "가공식품 이름 스캔 확정 결과")
data class ProcessedFoodNameConfirmResponse(
    val analysisId: String,
    val state: ProcessedFoodNameScanState,
    @field:Schema(description = "이번 호출에서 스캔 1회를 차감했는지 — 같은 분석을 같은 품목으로 다시 확정하면 false")
    val charged: Boolean,
    @field:Schema(description = "확정 품목 상세(가공식품 상세와 같은 응답). REJECTED 면 null", nullable = true)
    val product: ProcessedFoodDetailResponse?,
)
