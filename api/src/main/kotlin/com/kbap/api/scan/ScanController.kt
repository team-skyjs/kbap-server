package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.scanlog.ScanMode
import com.kbap.api.scanlog.ScanResultRecorder
import com.kbap.api.scanlog.ScanResultSummaries
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/scans")
class ScanController(
    private val scanFacade: ScanFacade,
    private val scanResultRecorder: ScanResultRecorder,
) : ScanApi {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val result = scanResultRecorder.record(memberId, ScanMode.MENU_V1, request.imagePath, ScanResultSummaries::menu) {
            scanFacade.scanMenuBoardImage(
                memberId,
                request.imagePath!!,
                request.toOcrItems(),
                LanguageCode.from(langRequest.lang),
            )
        }
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
