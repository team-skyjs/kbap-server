package com.kbap.api.translation

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberIdOrNull
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API, version = "1.0+")
class TranslationController(
    private val translationService: TranslationService,
) : TranslationApi {
    @PostMapping("/translations")
    override fun translate(
        @AuthMemberIdOrNull memberId: Long?,
        @RequestParam lang: String,
        @Valid @RequestBody request: TranslationRequest,
    ): ResponseEntity<BaseResponse<TranslationResponse>> {
        val language = LanguageCode.from(lang)
        val translated = translationService.translate(memberId, request.targetType!!, request.targetId!!, language)
        return ResponseEntity.ok(
            BaseResponse.ok(TranslationResponse(request.targetType.name, request.targetId, language.code, translated.text, translated.sourceLanguage)),
        )
    }
}
