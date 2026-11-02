package com.kbap.api.processedfood

import java.time.Instant
import com.kbap.common.domain.ingredient.label.IngredientLabelSource
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "성분표 분석 요청 — 앱 온디바이스 OCR 원문(여러 면)")
data class ProcessedFoodLabelScanRequest(
    @field:Schema(description = "이어 붙일 분석 ID — 이름 스캔 NO_CANDIDATE·REJECTED 또는 이전 성분표 결과. 생략·null 이면 새 분석", nullable = true)
    val analysisId: String? = null,

    @field:Schema(description = "앱이 만든 UUID — 새 분석(analysisId 없음)일 때 필수인 멱등 키. 같은 키·같은 원문 재전송은 같은 분석(charged=false), 같은 키·다른 원문은 409 SCAN-005. 이어 붙이기(analysisId 있음)에서는 무시", nullable = true, example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10")
    val clientRequestId: String? = null,

    @field:NotNull(message = "pages 는 필수입니다")
    @field:Size(min = 1, max = 4, message = "성분표 면은 1~4개여야 합니다")
    @field:Valid
    @field:Schema(description = "성분표 면 OCR 원문 1~4개(촬영 순서)", requiredMode = Schema.RequiredMode.REQUIRED)
    val pages: List<ProcessedFoodLabelPage>? = null,

    @field:Schema(description = "앱 언어 코드(분석 기록용). 미지원·생략은 en", example = "ko")
    val lang: String? = null,
)

@Schema(description = "성분표 한 면")
data class ProcessedFoodLabelPage(
    @field:NotBlank(message = "ocrText 는 필수입니다")
    @field:Size(max = 4000, message = "ocrText 는 4000자 이하여야 합니다")
    @field:Schema(description = "그 면의 OCR 원문(줄바꿈 포함 그대로, 4000자 이하)", example = "원재료명: 소맥분(밀), 새우 10%\n알레르기 유발물질: 밀, 새우 함유", requiredMode = Schema.RequiredMode.REQUIRED)
    val ocrText: String? = null,
)

@Schema(description = "성분표 분석 결과 — 검출되지 않은 재료는 '확인 안 됨'이지 '없음/안전'이 아니다")
data class ProcessedFoodLabelScanResponse(
    @field:Schema(description = "분석 ID — 같은 ID 로 다시 보내면 결과만 갱신(차감 0)")
    val analysisId: String,
    @field:Schema(description = "항상 LABEL_RESULT")
    val state: ProcessedFoodNameScanState,
    @field:Schema(description = "판독 근거 — DECLARED(알레르기 표시란 줄이 있음) · INGREDIENTS_ONLY(원재료로만) · NONE")
    val labelSource: ProcessedFoodLabelSource,
    @field:Schema(description = "검출 재료(근거 원문 포함)")
    val detections: List<ProcessedFoodDetectionResponse>,
    @field:Schema(description = "폐기(KB-866) — 81종 밖 원재료는 판정에서 빠져 항상 빈 배열이다. 앱 호환을 위해 남겨 둔다", deprecated = true)
    val unmapped: List<String>,
    @field:Schema(description = "같은 제조시설·혼입 등 '함유 가능' 표기에서 읽힌 재료 — 검출로 세지 않고 회피 대조에도 넣지 않는 참고 정보")
    val mayContain: List<ProcessedFoodMayContainResponse>,
    @field:Schema(description = "회원 회피 재료 대조(가공식품 상세와 같은 형태) — DETECTED / UNCONFIRMED 만 있다")
    val avoidance: List<ProcessedFoodAvoidanceResponse>,
    @field:Schema(description = "정리된 원문(면 사이 중복 줄 제거, 줄 순서 유지)")
    val rawText: String,
    @field:Schema(description = "이번 호출에서 1회 차감했는지")
    val charged: Boolean,
    @field:Schema(description = "내 판정(가공식품 상세와 같은 규칙) — 회피 재료 0개·읽은 원재료와 알레르기 표시가 모두 없으면 null", nullable = true)
    val verdict: ProcessedFoodVerdict?,
    val avoidMatches: List<ProcessedFoodVerdictMatch>,
    val mayContainMatches: List<ProcessedFoodVerdictMatch>,
    val unclearIngredients: List<String>,
    @field:Schema(description = "unclearIngredients 에 걸린 서버 사전 용어와 요청 lang 라벨(가공식품 상세와 같은 형태)")
    val unclearIngredientLabels: List<UnclearIngredientLabel> = emptyList(),
    val majorAllergens: List<IngredientCode>,
    @field:Schema(description = "가공식품 1시간 무료 창이 끝나는 시각(ISO) — 차감된 가공식품 스캔 성공부터 고정 60분, 창 안의 가공식품 스캔은 차감 0(charged=false)·남은 횟수 0 이어도 통과. 창이 없으면 null", example = "2026-10-27T04:00:00Z", nullable = true)
    val freeUntil: Instant? = null,
)

@Schema(description = "함유 가능 표기 재료")
data class ProcessedFoodMayContainResponse(
    val code: IngredientCode,
    val source: IngredientLabelSource,
    @field:Schema(description = "근거 원문")
    val evidence: String,
)
