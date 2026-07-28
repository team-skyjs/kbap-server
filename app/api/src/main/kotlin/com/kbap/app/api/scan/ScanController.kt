package com.kbap.app.api.scan

import com.kbap.domain.scan.ScanService
import com.kbap.app.api.common.ApiPaths
import com.kbap.app.api.common.BaseResponse
import com.kbap.app.api.common.auth.AuthMemberId
import com.kbap.common.core.lang.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.V1 + "/scans")
class ScanController(
    private val scanService: ScanService,
) : ScanApi {
    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val result = scanService.scanMenuBoardImage(
            memberId,
            request.imagePath!!,
            request.toOcrItems(),
            LanguageCode.from(langRequest.lang),
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
