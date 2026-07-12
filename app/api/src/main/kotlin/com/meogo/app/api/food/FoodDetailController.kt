package com.meogo.app.api.food

import com.meogo.application.client.food.dto.GetFoodDetailInput
import com.meogo.application.client.food.usecase.GetFoodDetailUseCase
import com.meogo.app.api.common.ApiPaths
import com.meogo.app.api.common.BaseResponse
import com.meogo.app.api.common.auth.AuthMemberIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/foods")
class FoodDetailController(
    private val getFoodDetailUseCase: GetFoodDetailUseCase,
) : FoodDetailApi {
    override fun detail(
        @PathVariable foodId: Long,
        @RequestParam(required = false) lang: String?,
        @AuthMemberIdOrNull memberId: Long?,
    ): ResponseEntity<BaseResponse<FoodDetailResponse>> {
        val result = getFoodDetailUseCase.getDetail(GetFoodDetailInput(foodId = foodId, lang = lang, memberId = memberId))
        return ResponseEntity.ok(BaseResponse.ok(FoodDetailResponse.from(result)))
    }
}
