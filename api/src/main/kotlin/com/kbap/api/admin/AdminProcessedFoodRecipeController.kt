package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.processedfood.model.ProcessedFoodRecipe
import com.kbap.common.domain.processedfood.model.RecipeMethod
import com.kbap.common.domain.processedfood.model.RecipeMethodType
import com.kbap.common.domain.processedfood.model.RecipePower
import com.kbap.common.domain.processedfood.model.RecipeStep
import com.kbap.common.domain.processedfood.model.RecipeWater
import com.kbap.common.domain.processedfood.model.RecipeWaterLine
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/{id}/recipe", version = "1.0+")
class AdminProcessedFoodRecipeController(
    private val manualService: AdminProcessedFoodManualService,
) : AdminProcessedFoodRecipeApi {
    @PutMapping
    override fun putRecipe(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminProcessedFoodRecipePutRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodRecipePutResponse>> =
        ResponseEntity.ok(BaseResponse.ok(manualService.putRecipe(id, request.recipe?.toDomain())))
}

@Schema(description = "가공식품 조리법 전체 교체 — recipe 가 null 이면 지운다")
data class AdminProcessedFoodRecipePutRequest(
    @field:Valid
    @field:Schema(description = "조리법(포장 표시 그대로) — null 이면 삭제", nullable = true)
    val recipe: RecipeRequest? = null,
)

@Schema(description = "조리법")
data class RecipeRequest(
    @field:Schema(description = "출처 — LABEL(포장 표시)", example = "LABEL")
    val source: String? = null,
    @field:NotNull @field:Size(min = 1, max = MAX_METHODS, message = "methods 는 1~$MAX_METHODS 개입니다") @field:Valid
    @field:Schema(description = "조리 방법 — 포장 표시 순서", requiredMode = Schema.RequiredMode.REQUIRED)
    val methods: List<RecipeMethodRequest>? = null,
) {
    fun toDomain() = ProcessedFoodRecipe(source = source?.takeIf { it.isNotBlank() } ?: ProcessedFoodRecipe.SOURCE_LABEL, methods = methods.orEmpty().map { it.toDomain() })

    companion object {
        const val MAX_METHODS = 4
    }
}

@Schema(description = "조리 방법 하나")
data class RecipeMethodRequest(
    @field:NotNull @field:Schema(description = "MICROWAVE·BOILING_WATER", requiredMode = Schema.RequiredMode.REQUIRED)
    val type: RecipeMethodType? = null,
    @field:Valid @field:Schema(description = "물 — ml 또는 선(INNER·OUTER·COVER_NOODLES), 둘 다 null 가능", nullable = true)
    val water: RecipeWaterRequest? = null,
    @field:Valid @field:Size(max = MAX_POWERS) @field:Schema(description = "와트별 시간(전자레인지) — 표시 순서")
    val power: List<RecipePowerRequest>? = null,
    @field:Min(0) @field:Max(MAX_SECONDS) @field:Schema(description = "기다리는 시간(초, 끓는 물)", nullable = true)
    val waitSeconds: Int? = null,
    @field:Min(0) @field:Max(MAX_ML) @field:Schema(description = "물을 버릴 때 남길 양(ml)", nullable = true)
    val drainKeepMl: Int? = null,
    @field:NotNull @field:Size(min = 1, max = MAX_STEPS, message = "steps 는 1~$MAX_STEPS 개입니다") @field:Valid
    @field:Schema(description = "단계 — 한국어 원문(번역은 서버)", requiredMode = Schema.RequiredMode.REQUIRED)
    val steps: List<RecipeStepRequest>? = null,
    @field:Size(max = MAX_CAUTIONS) @field:Schema(description = "주의 — 포장에 있을 때만, 한국어 원문")
    val cautions: List<@NotBlank @Size(max = MAX_TEXT) String>? = null,
) {
    fun toDomain() = RecipeMethod(
        type = type!!,
        water = water?.let { RecipeWater(it.ml, it.line) },
        power = power.orEmpty().map { RecipePower(it.watt!!, it.seconds!!) },
        waitSeconds = waitSeconds,
        drainKeepMl = drainKeepMl,
        steps = steps.orEmpty().map { RecipeStep(it.ko!!.trim()) },
        cautions = cautions.orEmpty().map { it.trim() },
    )

    companion object {
        const val MAX_POWERS = 4
        const val MAX_STEPS = 12
        const val MAX_CAUTIONS = 4
        const val MAX_SECONDS = 3600L
        const val MAX_ML = 2000L
        const val MAX_TEXT = 300
    }
}

data class RecipeWaterRequest(
    @field:Min(0) @field:Max(RecipeMethodRequest.MAX_ML) @field:Schema(description = "물 양(ml)", nullable = true)
    val ml: Int? = null,
    @field:Schema(description = "INNER(안쪽 선)·OUTER(바깥 선)·COVER_NOODLES(면이 잠길 만큼)", nullable = true)
    val line: RecipeWaterLine? = null,
)

data class RecipePowerRequest(
    @field:NotNull @field:Min(100) @field:Max(2000) @field:Schema(description = "와트", example = "1000")
    val watt: Int? = null,
    @field:NotNull @field:Min(0) @field:Max(RecipeMethodRequest.MAX_SECONDS) @field:Schema(description = "시간(초)", example = "150")
    val seconds: Int? = null,
)

data class RecipeStepRequest(
    @field:NotBlank @field:Size(max = RecipeMethodRequest.MAX_TEXT) @field:Schema(description = "한국어 원문", example = "뚜껑을 절취선까지 뜯는다")
    val ko: String? = null,
)

@Schema(description = "조리법 저장 결과")
data class AdminProcessedFoodRecipePutResponse(
    val processedFoodId: Long,
    @field:Schema(description = "저장된 조리법이 있으면 true, 지웠으면 false")
    val hasRecipe: Boolean,
)
