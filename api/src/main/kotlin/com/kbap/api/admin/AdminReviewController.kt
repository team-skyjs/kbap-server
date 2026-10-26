package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestAttribute
import com.kbap.api.core.auth.JwtAuthenticationFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/reviews", version = "1.0+")
class AdminReviewController(
    private val adminMemberQueryService: AdminMemberQueryService,
    private val deleteService: AdminReviewDeleteService,
) : AdminReviewApi {
    @DeleteMapping("/{id}")
    override fun deleteReview(
        @PathVariable id: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long,
    ): ResponseEntity<BaseResponse<Unit>> {
        deleteService.delete(adminId, id)
        return ResponseEntity.ok(BaseResponse.ok(Unit))
    }

    @GetMapping
    override fun getReviewPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) isBot: Boolean?,
    ): ResponseEntity<BaseResponse<AdminReviewPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminMemberQueryService.getReviewPage(page.coerceAtLeast(1), isBot)))
}
