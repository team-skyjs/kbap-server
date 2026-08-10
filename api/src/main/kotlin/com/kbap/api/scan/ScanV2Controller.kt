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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V2 + "/scans")
class ScanV2Controller(
    private val scanService: ScanService,
) : ScanV2Api {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanV2Request,
    ): ResponseEntity<BaseResponse<ScanV2Response>> {
        val result = scanService.scanMenuBoardImageV2(
            memberId,
            request.imagePath!!,
            LanguageCode.from(langRequest.lang),
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanV2Response.from(result)))
    }
}
