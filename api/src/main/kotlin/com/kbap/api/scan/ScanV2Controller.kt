package com.kbap.api.scan

import com.kbap.api.core.ApiHeaders
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.food.FoodProcessedLinkService
import com.kbap.api.scanlog.ScanMode
import com.kbap.api.scanlog.ScanResultRecorder
import com.kbap.api.scanlog.ScanResultSummaries
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.exchange.ExchangeRateClient
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import com.kbap.api.member.MemberService
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
    private val scanResultRecorder: ScanResultRecorder,
    private val menuNameEstimates: MenuNameEstimateService,
    private val memberService: MemberService,
    private val processedLinks: FoodProcessedLinkService,
) : ScanV2Api {
    @GetMapping("/{scanId}/pending")
    override fun getPending(
        @AuthMemberId memberId: Long,
        @PathVariable scanId: String,
    ): ResponseEntity<BaseResponse<ScanPendingResponse>> =
        ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(BaseResponse.ok(menuNameEstimates.getPendingItems(scanId, memberId, memberService.getAvoidance(memberId))))

    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestHeader(SCAN_TICKET_HEADER) scanTicket: String,
        @RequestHeader(ApiHeaders.API_VERSION) apiVersion: String,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @RequestParam currency: String,
        @Valid @RequestBody request: ScanV2Request,
    ): ResponseEntity<BaseResponse<ScanV2Response>> {
        val requestedCurrency = requestedCurrency(currency)
        val lang = LanguageCode.from(langRequest.lang)
        val result = scanResultRecorder.record(memberId, ScanMode.MENU_V2, request.imagePath, ScanResultSummaries::menu) {
            scanFacade.scanMenuBoardImageV2(
                memberId,
                request.imagePath!!,
                lang,
                scanTicket,
            )
        }
        val krwPerUnit = exchangeRateClient.getKrwPerUnitOrNull(requestedCurrency)
        val exposeSubstitution = ApiVersions.isAtLeast(apiVersion, ApiVersions.SCAN_SUBSTITUTION)
        val linked = processedLinks.getLinkedProcessedFoods(result.items.filter { it.matched }.mapNotNull { it.foodId }, lang)
        return ResponseEntity.ok(
            BaseResponse.ok(ScanV2Response.from(result, requestedCurrency, krwPerUnit, exposeSubstitution, linked)),
        )
    }

    private fun requestedCurrency(raw: String): CurrencyCode =
        CurrencyCode.from(raw) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE)
}
