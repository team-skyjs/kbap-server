package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
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
@RequestMapping(ApiPaths.API + "/scans")
class ScanController(
    private val scanService: ScanService,
) : ScanApi {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestHeader(IDEMPOTENCY_KEY_HEADER, required = false) idempotencyKey: String?,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val result = scanService.scanMenuBoardImage(
            memberId,
            request.imagePath!!,
            request.toOcrItems(),
            LanguageCode.from(langRequest.lang),
            idempotencyKey,
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
