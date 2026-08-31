package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.exchange.ExchangeRateClient
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/scans", version = "2.0+")
class ScanV2Controller(
    private val scanFacade: ScanFacade,
    private val exchangeRateClient: ExchangeRateClient,
) : ScanV2Api {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestHeader(SCAN_TICKET_HEADER) scanTicket: String,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @RequestParam currency: String,
        @Valid @RequestBody request: ScanV2Request,
    ): ResponseEntity<BaseResponse<ScanV2Response>> {
        val requestedCurrency = requestedCurrency(currency)
        val result = scanFacade.scanMenuBoardImageV2(
            memberId,
            request.imagePath!!,
            LanguageCode.from(langRequest.lang),
            scanTicket,
        )
        val krwPerUnit = exchangeRateClient.getKrwPerUnitOrNull(requestedCurrency)
        return ResponseEntity.ok(BaseResponse.ok(ScanV2Response.from(result, requestedCurrency, krwPerUnit)))
    }

    private fun requestedCurrency(raw: String): CurrencyCode =
        CurrencyCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE)
}
