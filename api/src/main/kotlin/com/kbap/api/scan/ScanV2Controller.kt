package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/scans", version = "2.0+")
class ScanV2Controller(
    private val scanService: ScanService,
) : ScanV2Api {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @RequestParam(required = false) currency: String?,
        @Valid @RequestBody request: ScanV2Request,
    ): ResponseEntity<BaseResponse<ScanV2Response>> {
        val result = scanService.scanMenuBoardImageV2(
            memberId,
            request.imagePath!!,
            LanguageCode.from(langRequest.lang),
            requestedCurrency(currency),
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanV2Response.from(result)))
    }

    private fun requestedCurrency(raw: String?): CurrencyCode? =
        raw?.let { CurrencyCode.from(it) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE) }
}
