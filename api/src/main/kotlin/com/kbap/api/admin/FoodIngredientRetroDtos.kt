package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "새 재료 소급 판정 시작 요청")
data class FoodIngredientRetroRequest(
    @field:NotEmpty(message = "codes 는 1개 이상이어야 합니다")
    @field:Size(max = 100, message = "codes 는 100개 이하여야 합니다")
    @field:Schema(description = "판정할 재료 코드(카탈로그 코드, 1개 이상)", example = "[\"CORIANDER\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    val codes: List<String>? = null,
    @field:NotNull(message = "dryRun 은 필수입니다")
    @field:Schema(description = "true 면 호출 0회로 대상 수·예상 비용만 센다", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    val dryRun: Boolean? = null,
    @field:Schema(description = "true 면 판정 기록·이미 있는 코드를 무시하고 대상 전부를 다시 판정해 그 코드 항목을 교체한다 — round(p×100) 이 반영 기준(thresholdPercent) 이상이면 비율 갱신·추가, 미만이면 제거. dryRun 과 함께면 호출·교체 0(예상만). 기본 false", example = "false", nullable = true)
    val rejudge: Boolean? = null,
    @field:Size(max = 10000, message = "foodIds 는 10000개 이하여야 합니다")
    @field:Schema(description = "대상 음식 id(선택). 생략하면 READY 전부, 주면 그중 READY 인 것만", example = "[101, 102]", nullable = true)
    val foodIds: List<Long>? = null,
)

@Schema(description = "소급 판정 시작 결과")
data class FoodIngredientRetroStartResponse(
    @field:Schema(description = "작업 id — GET /api/admin/foods/ingredients/retro/{jobId} 로 진행·결과를 본다")
    val jobId: String,
    @field:Schema(description = "이 작업에 적용된 반영 기준(%) — round(p×100) 이 이 값 이상이면 붙인다", example = "30")
    val thresholdPercent: Int,
)

@Schema(description = "소급 판정 작업 진행·결과(Redis 에 7일 보관)")
data class FoodIngredientRetroJob(
    val jobId: String,
    val codes: List<String>,
    val dryRun: Boolean,
    val rejudge: Boolean = false,
    @field:Schema(description = "이 작업에 적용된 반영 기준(%) — round(p×100) 이 이 값 이상이면 붙인다(설정 kbap.food.ingredient-retro.min-percent, 기본 30). 기준 도입 전 작업은 1")
    val thresholdPercent: Int = 1,
    @field:Schema(description = "RUNNING · DONE · FAILED(중단·배포 종료 — 이미 반영된 음식은 그대로, 같은 요청을 다시 보내면 남은 것만 이어 간다)")
    val status: Status,
    val startedAt: String,
    val finishedAt: String? = null,
    @field:Schema(description = "범위 안 READY 음식 수")
    val readyCount: Int = 0,
    @field:Schema(description = "호출 대상 음식 수 — 그 코드가 아직 없고 지금 재료 목록(version)으로 판정한 적 없는 READY 음식. rejudge 면 재료가 평가된 READY 음식 전부")
    val targetCount: Int = 0,
    @field:Schema(description = "실제 호출 수(dryRun 이면 0)")
    val calledCount: Int = 0,
    @field:Schema(description = "코드가 1개 이상 추가된 음식 수")
    val addedFoodCount: Int = 0,
    @field:Schema(description = "추가된 (음식, 코드) 수")
    val addedCodeCount: Int = 0,
    @field:Schema(description = "rejudge 로 제거된 (음식, 코드) 수")
    val removedCodeCount: Int = 0,
    @field:Schema(description = "재료 21개 상한 때문에 건너뛴 음식 id — 사람 판단 필요")
    val skippedCapFoodIds: List<Long> = emptyList(),
    @field:Schema(description = "재료가 아직 평가되지 않은(null) READY 음식 id — 부분 목록이 되면 다른 회피 재료가 UNKNOWN 대신 SAFE 가 되므로 붙이지 않는다(재수집으로 전체 평가)")
    val skippedUnassessedFoodIds: List<Long> = emptyList(),
    @field:Schema(description = "호출 또는 반영 쓰기가 실패했거나 판정 중에 음식이 수정돼(version 변경) 옛 판정을 버린 음식 id — 판정 기록을 남기지 않아 다시 돌리면 이것만 다시 부른다(호출 뒤 쓰기 실패는 호출·비용 합계에 포함)")
    val failedFoodIds: List<Long> = emptyList(),
    @field:Schema(description = "코드별 확률 분포 — lt1(<1%) · 1to10 · 10to50 · 50to90 · ge90(≥90%)")
    val probabilityBuckets: Map<String, Map<String, Int>> = emptyMap(),
    @field:Schema(description = "실제 비용 합계(원, llm_call_cost 와 같은 계산)")
    val costKrw: Double = 0.0,
    @field:Schema(description = "예상 비용(원) = 대상 수 × 1회 추정 단가")
    val estimatedCostKrw: Double = 0.0,
) {
    enum class Status { RUNNING, DONE, FAILED }
}
