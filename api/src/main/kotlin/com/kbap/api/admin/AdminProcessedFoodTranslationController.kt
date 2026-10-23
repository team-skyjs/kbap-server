package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.processedfood.ProcessedFoodTranslationService
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
) : AdminProcessedFoodTranslationApi {
    @PostMapping("/prewarm")
    override fun prewarm(@RequestParam(required = false) languages: String?): ResponseEntity<BaseResponse<AdminProcessedFoodPrewarmResponse>> {
        val targets = languages?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map(LanguageCode::from)?.distinct()
            ?: LanguageCode.entries.filter { it != LanguageCode.KO }
        translationService.prewarmFeaturedInBackground(targets)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.ok(AdminProcessedFoodPrewarmResponse(targets.map { it.code })))
    }
}

data class AdminProcessedFoodPrewarmResponse(val languages: List<String>)
