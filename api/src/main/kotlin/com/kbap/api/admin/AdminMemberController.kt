package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import com.kbap.api.core.auth.JwtAuthenticationFilter
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/members", version = "1.0+")
class AdminMemberController(
    private val adminMemberQueryService: AdminMemberQueryService,
    private val scanUnlockResetService: AdminScanUnlockResetService,
) : AdminMemberApi {
    @PostMapping("/{id}/scan-unlock/reset")
    override fun resetScanUnlock(
        @PathVariable id: Long,
        @RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminAccountId: Long,
    ): ResponseEntity<BaseResponse<AdminScanUnlockResetResponse>> =
        ResponseEntity.ok(BaseResponse.ok(scanUnlockResetService.resetScanUnlock(adminAccountId, id)))

    @GetMapping
    override fun searchMemberPage(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) isBot: Boolean?,
        @RequestParam(required = false) country: String?,
    ): ResponseEntity<BaseResponse<AdminMemberListResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(adminMemberQueryService.searchMemberPage(page.coerceAtLeast(1), q, isBot, country)),
        )

    @GetMapping("/country-counts")
    override fun getCountryCounts(
        @RequestParam(required = false) isBot: Boolean?,
    ): ResponseEntity<BaseResponse<List<AdminMemberCountryCountResponse>>> =
        ResponseEntity.ok(BaseResponse.ok(adminMemberQueryService.getCountryCounts(isBot)))

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

    @GetMapping("/{id}/scan-photos")
    @io.swagger.v3.oas.annotations.Operation(summary = "회원 스캔 사진 갤러리", description = "ADMIN 전용. 단일·다중 스캔 저장 사진을 경로로 중복 제거해 최신순 24개씩 조회한다.")
    fun getScanPhotos(@PathVariable id: Long, @RequestParam(defaultValue = "1") page: Int): ResponseEntity<BaseResponse<AdminScanPhotoPage>> =
        ResponseEntity.ok(BaseResponse.ok(adminMemberQueryService.getScanPhotoPage(id, page.coerceAtLeast(1))))

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
