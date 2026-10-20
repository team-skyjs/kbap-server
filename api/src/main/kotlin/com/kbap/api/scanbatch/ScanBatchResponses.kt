package com.kbap.api.scanbatch

import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.domain.scan.model.ScanPhotoState
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

enum class ScanBatchLifecycle { ACTIVE, CANCELLED, EXPIRED }

@Schema(description = "스캔 묶음 — 저장본 재조회(멱등, 유료 호출 없음)")
data class ScanBatchResponse(
    @field:Schema(description = "묶음 id(생성 요청의 clientRequestId)", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10")
    val batchId: String,
    @field:Schema(description = "ACTIVE(실행·재시도 가능) / CANCELLED / EXPIRED(실행 창 15분 경과 — 결과는 7일 조회 가능)")
    val lifecycle: ScanBatchLifecycle,
    @field:Schema(description = "실행·재시도 가능 기한")
    val expiresAt: LocalDateTime,
    @field:Schema(description = "이 묶음으로 1회가 차감됐는지 — 한 장이라도 결과가 저장되면 true(묶음당 최대 1회)")
    val usageRecorded: Boolean,
    @field:Schema(description = "결과 개정 번호 — 사진 결과가 저장될 때마다 1 씩 오른다")
    val resultRevision: Long,
    @field:Schema(description = "사진별 상태(선택 순서)")
    val photos: List<ScanBatchPhotoResponse>,
) {
    companion object {
        fun of(operation: ScanOperation, batchId: UUID, photos: List<ScanOperationPhoto>, now: LocalDateTime): ScanBatchResponse {
            val lifecycle = when {
                operation.lifecycle == ScanBatchService.CANCELLED -> ScanBatchLifecycle.CANCELLED
                !now.isBefore(operation.expiresAt) -> ScanBatchLifecycle.EXPIRED
                else -> ScanBatchLifecycle.ACTIVE
            }
            return ScanBatchResponse(
                batchId = batchId.toString(),
                lifecycle = lifecycle,
                expiresAt = operation.expiresAt,
                usageRecorded = operation.usageRecordedAt != null,
                resultRevision = operation.resultRevision,
                photos = photos.map { ScanBatchPhotoResponse.of(it, now, lifecycle == ScanBatchLifecycle.ACTIVE) },
            )
        }
    }
}

@Schema(description = "묶음 안 사진 한 장의 상태")
data class ScanBatchPhotoResponse(
    @field:Schema(description = "선택 순서(0부터) — 재시도 경로의 {index}", example = "0")
    val index: Int,
    @field:Schema(description = "앱이 보낸 사진 id", example = "6a1f0e1c-2b3d-4c5e-8f90-0a1b2c3d4e5f")
    val photoId: String,
    @field:Schema(description = "PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED — 시도 기한이 지난 RUNNING 은 FAILED(LEASE_EXPIRED) 로 보인다")
    val state: ScanPhotoState,
    @field:Schema(description = "지금까지 시작한 시도 수", example = "1")
    val attemptNo: Int,
    @field:Schema(description = "실패 사유(QUOTA_EXHAUSTED·LEASE_EXPIRED·인식 실패 코드) — 실패가 아니면 null", nullable = true)
    val errorReason: String?,
    @field:Schema(description = "같은 묶음에서 다시 시도할 수 있는지(추가 차감 없음)")
    val retryable: Boolean,
) {
    companion object {
        fun of(photo: ScanOperationPhoto, now: LocalDateTime, batchActive: Boolean): ScanBatchPhotoResponse {
            val leaseExpired = photo.executionState == ScanPhotoState.RUNNING && photo.leaseUntil?.isBefore(now) == true
            val state = if (leaseExpired) ScanPhotoState.FAILED else photo.executionState
            val reason = if (leaseExpired) ScanOperationPhoto.LEASE_EXPIRED else photo.errorReason
            return ScanBatchPhotoResponse(
                index = photo.selectionIndex,
                photoId = ScanBatchService.uuidOf(photo.photoId).toString(),
                state = state,
                attemptNo = photo.attemptNo,
                errorReason = reason,
                retryable = batchActive && state == ScanPhotoState.FAILED && reason != ScanOperationPhoto.QUOTA_EXHAUSTED,
            )
        }
    }
}
