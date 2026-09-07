package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/members", version = "1.0+")
class AdminMemberController(
    private val adminMemberQueryService: AdminMemberQueryService,
) : AdminMemberApi {
    @GetMapping
    override fun searchMemberPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<BaseResponse<AdminMemberListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminMemberQueryService.searchMemberPage(page.coerceAtLeast(1), q)),
        )

    @GetMapping("/{id}")
    override fun getMemberDetail(
        @PathVariable id: Long,
    ): ResponseEntity<BaseResponse<AdminMemberDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(adminMemberQueryService.getMemberDetail(id)))

    @GetMapping("/{id}/reviews")
    override fun getMemberReviewPage(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "1") page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberReviewPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminMemberQueryService.getMemberReviewPage(id, page.coerceAtLeast(1))),
        )

    @GetMapping("/{id}/scans")
    override fun getMemberScanPage(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "1") page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberScanPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminMemberQueryService.getMemberScanPage(id, page.coerceAtLeast(1))),
        )

    @GetMapping("/{id}/orders")
    override fun getMemberOrderPage(
        @PathVariable id: Long,
        @RequestParam(defaultValue = "1") page: Int,
    ): ResponseEntity<BaseResponse<AdminMemberOrderPageResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminMemberQueryService.getMemberOrderPage(id, page.coerceAtLeast(1))),
        )
}
