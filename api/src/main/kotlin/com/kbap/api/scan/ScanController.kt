package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersion
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.MenuBoardReadingMode
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
    private val photoOnlyReadingSince = ApiVersion(2026, 8, 8)

    @PostMapping
    override fun scan(
        @AuthMemberId memberId: Long,
        @RequestHeader(value = "X-API-Version", required = false) apiVersion: String?,
        @Valid @ModelAttribute langRequest: ScanLangRequest,
        @Valid @RequestBody request: ScanRequest,
    ): ResponseEntity<BaseResponse<ScanResponse>> {
        val readingMode =
            if (ApiVersion.parseOrNull(apiVersion)?.let { it >= photoOnlyReadingSince } == true) {
                MenuBoardReadingMode.PHOTO_ONLY
            } else {
                MenuBoardReadingMode.OCR_ASSISTED
            }
        val result = scanService.scanMenuBoardImage(
            memberId,
            request.imagePath!!,
            request.toOcrItems(),
            LanguageCode.from(langRequest.lang),
            readingMode,
        )
        return ResponseEntity.ok(BaseResponse.ok(ScanResponse.from(result)))
    }
}
