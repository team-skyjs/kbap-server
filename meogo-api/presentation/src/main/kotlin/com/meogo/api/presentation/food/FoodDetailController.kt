package com.meogo.api.presentation.food

import com.meogo.api.application.food.GetFoodDetailInput
import com.meogo.api.application.food.GetFoodDetailUseCase
import com.meogo.api.presentation.common.ApiPaths
import com.meogo.api.presentation.common.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodDetailController(
    private val getFoodDetailUseCase: GetFoodDetailUseCase,
) : FoodDetailApi {
    override fun detail(
        @RequestParam menuName: String,
        @RequestParam(required = false) lang: String?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>> {
        require(menuName.isNotBlank()) { "menuName은 필수입니다" }

        val result = getFoodDetailUseCase.getDetail(GetFoodDetailInput(menuName = menuName, lang = lang))
        return ResponseEntity.ok(BaseResponse.ok(FoodDetailResponse.from(result)))
    }
}
