package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.processedfood.ProcessedFoodNameRegeneration
import com.kbap.api.processedfood.ProcessedFoodTranslationService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import com.kbap.common.domain.LanguageCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/processed-foods/translations", version = "1.0+")
class AdminProcessedFoodTranslationController(
    private val translationService: ProcessedFoodTranslationService,
    @Value("\${kbap.curation.environment:disabled}") private val environment: String,
) : AdminProcessedFoodTranslationApi {
    @PostMapping("/names/regenerate")
    override fun regenerateNames(
        @RequestHeader("X-Target-Environment") target: String,
        @Valid @RequestBody request: AdminProcessedFoodNameRegenerateRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodNameRegeneration>> {
        if (target != environment) throw BusinessException(ErrorCode.PROCESSED_FOOD_ENVIRONMENT_MISMATCH)
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(BaseResponse.ok(translationService.regenerateNames(request.dryRun, request.limit, request.afterId)))
    }

    @PostMapping("/prewarm")
    override fun prewarm(@RequestParam(required = false) languages: String?): ResponseEntity<BaseResponse<AdminProcessedFoodPrewarmResponse>> {
        val targets = languages?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(LanguageCode::from)?.distinct()
            ?: LanguageCode.entries.filter { it != LanguageCode.KO }
        translationService.prewarmFeaturedInBackground(targets)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.ok(AdminProcessedFoodPrewarmResponse(targets.map { it.code })))
    }
}

data class AdminProcessedFoodPrewarmResponse(val languages: List<String>)
