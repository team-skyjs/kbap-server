package com.kbap.api.ingredient

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/ingredients")
class IngredientController(
    private val ingredientQueryService: IngredientQueryService,
) : IngredientApi {
    @GetMapping
    override fun getIngredients(
        @Valid @ModelAttribute request: IngredientListRequest,
    ): ResponseEntity<BaseResponse<IngredientListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(ingredientQueryService.getIngredients(LanguageCode.from(request.lang))),
        )
}
