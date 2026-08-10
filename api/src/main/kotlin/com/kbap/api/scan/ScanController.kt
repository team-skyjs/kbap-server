package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersion
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/scans")
class ScanController(
    private val scanService: ScanService,
) : ScanApi {
    // 2026.08.07 계약부터 서버 OCR·유사 음식 폴백. 미전송·이전 버전·파싱 불가는 종전 계약(클라이언트 OCR)으로 폴백.
    private val serverOcrSince = ApiVersion(2026, 8, 7)

    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestHeader(value = "X-API-Version", required = false) apiVersion: String?,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val serverOcr = ApiVersion.parseOrNull(apiVersion)?.let { it >= serverOcrSince } == true
        if (!serverOcr && request.items.isNullOrEmpty()) throw BusinessException(ErrorCode.INVALID_REQUEST)

        val result = scanService.scanMenuBoardImage(
            memberId = memberId,
            imagePath = request.imagePath!!,
            ocrItems = if (serverOcr) emptyList() else request.toOcrItems(),
            lang = LanguageCode.from(langRequest.lang),
            similarFoodFallback = serverOcr,
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
