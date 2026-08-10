package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods/contents")
class AdminFoodContentIngestController(
    private val adminFoodContentIngestService: AdminFoodContentIngestService,
) : AdminFoodContentIngestApi {
    @PostMapping
    override fun ingestContent(
        @Valid @RequestBody request: AdminFoodContentIngestRequest,
    ): ResponseEntity<BaseResponse<Unit>> {
        val foodId = request.foodId!!
        if (request.passed!!) {
            adminFoodContentIngestService.ingestContent(
                foodId = foodId,
                description = request.description!!,
                spiciness = request.spiciness!!,
                nameTranslations = request.nameTranslations!!,
                descriptionTranslations = request.descriptionTranslations!!,
                ingredients = request.ingredients!!,
            )
        } else {
            adminFoodContentIngestService.ingestFailure(
                foodId = foodId,
                failureKind = request.failureKind!!,
                reason = request.reason!!,
            )
        }
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }
}
