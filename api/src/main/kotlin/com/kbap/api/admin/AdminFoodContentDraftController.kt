package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodContentDraftController(
    private val draftService: AdminFoodContentDraftService,
) : AdminFoodContentDraftApi {
    @GetMapping("/content-drafts")
    override fun getContentDrafts(
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminFoodContentDraftPageResponse.from(
                    draftService.getDraftPage(
                        page = page?.coerceIn(0, MAX_PAGE) ?: 0,
                        size = size?.coerceIn(1, AdminFoodContentDraftService.DEFAULT_PAGE_SIZE) ?: AdminFoodContentDraftService.DEFAULT_PAGE_SIZE,
                    ),
                ),
            ),
        )

    @GetMapping("/{foodId}/content-draft")
    override fun getContentDraft(
        @PathVariable foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftResponse>> {
        val (food, draft) = draftService.getDraft(foodId)
        return ResponseEntity.ok(BaseResponse.ok(AdminFoodContentDraftResponse.from(food, draft)))
    }

    @PatchMapping("/{foodId}/content-draft")
    override fun reviewContentDraft(
        @PathVariable foodId: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
        @Valid @RequestBody request: AdminFoodContentDraftReviewRequest,
    ): ResponseEntity<BaseResponse<AdminFoodContentDraftReviewResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                AdminFoodContentDraftReviewResponse.from(draftService.reviewDraft(foodId, request.draftId!!, request.foodVersion!!, request.passed!!, request.reason, adminAccountId)),
            ),
        )

    private companion object {
        const val MAX_PAGE = 100_000
    }
}
