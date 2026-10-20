package com.kbap.api.processedfood

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(ApiPaths.API + "/processed-foods/scan")
class ProcessedFoodNameScanController(
    private val nameScanService: ProcessedFoodNameScanService,
    private val labelScanService: ProcessedFoodLabelScanService,
) : ProcessedFoodNameScanApi {
    @PostMapping("/label", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun scanLabel(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodLabelScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodLabelScanResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                labelScanService.scanLabel(
                    memberId,
                    request.analysisId,
                    if (request.analysisId == null) uuidOrInvalid(request.clientRequestId) else null,
                    request.pages!!.map { it.ocrText!! },
                    LanguageCode.from(request.lang ?: "en").code,
                ),
            ),
        )

    @PostMapping("/name", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun scanName(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodNameScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodNameScanResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(nameScanService.scanName(memberId, request.ocrText.orEmpty(), LanguageCode.from(request.lang.orEmpty()).code)),
        )

    @PostMapping("/confirm", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun confirm(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodNameConfirmRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodNameConfirmResponse>> =
        ResponseEntity.ok(BaseResponse.ok(nameScanService.confirm(memberId, request.analysisId.orEmpty(), request.reportNo)))

    private fun uuidOrInvalid(raw: String?): UUID =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { uuid -> uuid.toString() == it.lowercase() } } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
}
