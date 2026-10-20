package com.kbap.api.scanbatch

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.ApiVersions
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.scan.model.ScanPhotoState
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping(ApiPaths.API + "/scans/batches")
class ScanBatchController(
    private val scanBatchService: ScanBatchService,
    private val runner: ScanBatchRunner,
) : ScanBatchApi {
    @PostMapping(version = ApiVersions.SCAN_BATCH + "+")
    override fun create(
        @AuthMemberId memberId: Long,
        @Valid @RequestBody request: ScanBatchCreateRequest,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>> {
        val currency = CurrencyCode.from(request.currency!!) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE)
        val photos = request.photos!!.map { ScanBatchPhotoInput(uuidOrInvalid(it.photoId!!), it.imagePath!!) }
        val batchId = uuidOrInvalid(request.clientRequestId!!)
        val batch = scanBatchService.createBatch(memberId, batchId, LanguageCode.from(request.lang!!).code, currency.name, photos)
        runner.dispatch(memberId, batchId, batch.photos.filter { it.state == ScanPhotoState.PENDING }.map { it.index })
        return ResponseEntity.ok(BaseResponse.ok(batch))
    }

    @PostMapping("/{batchId}/photos/{index}/retry", version = ApiVersions.SCAN_BATCH + "+")
    override fun retry(
        @AuthMemberId memberId: Long,
        @PathVariable batchId: String,
        @PathVariable index: Int,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>> {
        runner.runClaimed(scanBatchService.retryPhoto(memberId, batchId, index))
        return ResponseEntity.ok(BaseResponse.ok(scanBatchService.getBatch(memberId, batchId)))
    }

    @GetMapping("/{batchId}", version = ApiVersions.SCAN_BATCH + "+")
    override fun get(
        @AuthMemberId memberId: Long,
        @PathVariable batchId: String,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>> =
        ResponseEntity.ok(BaseResponse.ok(scanBatchService.getBatch(memberId, batchId)))

    @PostMapping("/{batchId}/cancel", version = ApiVersions.SCAN_BATCH + "+")
    override fun cancel(
        @AuthMemberId memberId: Long,
        @PathVariable batchId: String,
    ): ResponseEntity<BaseResponse<ScanBatchResponse>> =
        ResponseEntity.ok(BaseResponse.ok(scanBatchService.cancelBatch(memberId, batchId)))

    private fun uuidOrInvalid(raw: String): UUID =
        runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
}
