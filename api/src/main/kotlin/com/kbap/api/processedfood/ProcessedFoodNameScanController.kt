package com.kbap.api.processedfood

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.api.scanlog.ScanMode
import com.kbap.api.scanlog.ScanResultRecorder
import com.kbap.api.scanlog.ScanResultSummaries
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
    private val translationService: ProcessedFoodTranslationService,
    private val visionScanService: ProcessedFoodVisionScanService,
    private val scanResultRecorder: ScanResultRecorder,
) : ProcessedFoodNameScanApi {
    @PostMapping("/vision", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun scanVision(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodVisionScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodVisionScanResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                scanResultRecorder.record(memberId, ScanMode.PFOOD_VISION, request.imagePath, ScanResultSummaries::vision) {
                    visionScanService.scanVision(
                        memberId,
                        request.imagePath!!.trim().trimStart('/'),
                        uuidOrInvalid(request.clientRequestId),
                        LanguageCode.from(request.lang ?: "en"),
                        menuFastPathEligible = request.menuFastPathEligible == true,
                    )
                },
            ),
        )

    @PostMapping("/label", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun scanLabel(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodLabelScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodLabelScanResponse>> =
        ResponseEntity.ok(
            BaseResponse.ok(
                scanResultRecorder.record(memberId, ScanMode.PFOOD_LABEL, null, ScanResultSummaries::label) {
                    labelScanService.scanLabel(
                        memberId,
                        request.analysisId,
                        if (request.analysisId == null) uuidOrInvalid(request.clientRequestId) else null,
                        request.pages!!.map { it.ocrText!! },
                        LanguageCode.from(request.lang ?: "en").code,
                    )
                },
            ),
        )

    @PostMapping("/name", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun scanName(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodNameScanRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodNameScanResponse>> {
        val language = LanguageCode.from(request.lang.orEmpty())
        val imagePath = request.imagePath?.trim()?.trimStart('/')?.takeIf { it.isNotEmpty() }
        val scanned = scanResultRecorder.record(memberId, ScanMode.PFOOD_NAME, imagePath, ScanResultSummaries::nameScan) {
            nameScanService.scanName(memberId, request.ocrText.orEmpty(), language.code, imagePath)
        }
        return ResponseEntity.ok(
            BaseResponse.ok(scanned.copy(candidates = translationService.localizeSummaries(scanned.candidates, language))),
        )
    }

    @PostMapping("/confirm", version = ApiVersions.PROCESSED_FOOD + "+")
    override fun confirm(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ProcessedFoodNameConfirmRequest,
    ): ResponseEntity<BaseResponse<ProcessedFoodNameConfirmResponse>> {
        val confirmation = nameScanService.confirm(memberId, request.analysisId.orEmpty(), request.reportNo)
        val response = confirmation.response.let {
            it.copy(product = it.product?.let { product -> translationService.localizeDetail(product, confirmation.language, member = true) })
        }
        return ResponseEntity.ok(BaseResponse.ok(response))
    }

    private fun uuidOrInvalid(raw: String?): UUID =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { uuid -> uuid.toString() == it.lowercase() } } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
}
