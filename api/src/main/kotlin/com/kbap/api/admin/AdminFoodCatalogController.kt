package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.FoodContentStatus
import jakarta.validation.Valid
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodCatalogController(
    private val adminFoodService: AdminFoodService,
) : AdminFoodCatalogApi {
    private val objectMapper = jacksonObjectMapper()

    @GetMapping
    override fun getFoodPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminFoodListResponse.from(adminFoodService.getFoodPage(page.coerceAtLeast(1), q, status)),
            ),
        )

    @GetMapping("/deleted")
    override fun getDeletedFoodPage(
        @RequestParam(defaultValue = "1") page: Int,
    ): ResponseEntity<BaseResponse<AdminFoodListResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.getDeletedFoodPage(page.coerceAtLeast(1))))

    @GetMapping("/deleted/{id}")
    override fun getDeletedFoodDetail(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.getDeletedFoodDetail(id)))

    @PostMapping("/{id}/restore")
    override fun restoreFood(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodRestoreResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.restoreFood(id)))

    @GetMapping("/{id}")
    override fun getFoodDetail(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminFoodService.getFoodDetail(id)))

    @PutMapping("/{id}")
    override fun updateFood(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminFoodUpdateRequest,
    ): ResponseEntity<BaseResponse<AdminFoodDetailResponse>> {
        val command = UpdateFoodCommand(
            koreanName = request.koreanName!!.trim(),
            displayName = request.displayName,
            description = request.description!!,
            spiciness = request.spiciness!!,
            contentStatus = request.contentStatus!!,
            imageRef = request.imageRef.orEmpty().trim(),
            nameTranslationsJson = request.nameTranslations?.let(objectMapper::writeValueAsString).orEmpty(),
            descriptionTranslationsJson = request.descriptionTranslations?.let(objectMapper::writeValueAsString).orEmpty(),
            ingredientsJson = request.ingredients?.let(objectMapper::writeValueAsString).orEmpty(),
        )
        val result = try {
            adminFoodService.updateFood(id, command, expectedVersion = request.version)
        } catch (e: OptimisticLockingFailureException) {
            throw BusinessException(ErrorCode.FOOD_VERSION_CONFLICT)
        }
        when (result) {
            AdminFoodUpdateResult.UPDATED -> Unit
            AdminFoodUpdateResult.NOT_FOUND -> throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
            AdminFoodUpdateResult.INVALID_NAME,
            AdminFoodUpdateResult.INVALID_JSON,
            -> throw BusinessException(ErrorCode.INVALID_REQUEST)
            AdminFoodUpdateResult.DUPLICATE_NAME -> throw BusinessException(ErrorCode.DUPLICATE_FOOD_NAME)
        }
        return ResponseEntity.ok(BaseResponse.ok(adminFoodService.getFoodDetail(id)))
    }

    @PostMapping("/{id}/recollect")
    override fun recollectFood(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminFoodRecollectResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminFoodRecollectResponse.from(adminFoodService.requestRecollectForFood(id))),
        )

    @PostMapping("/recollect")
    override fun recollectFoods(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: FoodContentStatus?,
    ): ResponseEntity<BaseResponse<AdminFoodRecollectResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(AdminFoodRecollectResponse.from(adminFoodService.requestRecollect(q, status))),
        )

    @DeleteMapping("/{id}")
    override fun deleteFood(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<Unit>> =
        when (adminFoodService.deleteFood(id)) {
            AdminFoodDeleteResult.DELETED -> ResponseEntity.ok(BaseResponse.ok(Unit))
            AdminFoodDeleteResult.NOT_FOUND -> throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        }
}
