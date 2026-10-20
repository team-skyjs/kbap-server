package com.kbap.api.scanbatch

import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanMergedItem
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.domain.scan.model.ScanPhotoState
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import java.util.UUID

enum class ScanBatchLifecycle { ACTIVE, CANCELLED, EXPIRED, QUOTA_EXHAUSTED }

@Schema(description = "스캔 묶음 — 저장본 재조회(멱등, 유료 호출 없음)")
data class ScanBatchResponse(
    @field:Schema(description = "묶음 id(생성 요청의 clientRequestId)", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10")
    val batchId: String,
    @field:Schema(description = "ACTIVE(실행·재시도 가능) / CANCELLED / EXPIRED(실행 창 15분 경과 — 결과는 7일 조회 가능) / QUOTA_EXHAUSTED(첫 결과 확정 시점에 무료 횟수가 없어 결과 없이 종료 — 남은 사진 CANCELLED)")
    val lifecycle: ScanBatchLifecycle,
    @field:Schema(description = "실행·재시도 가능 기한")
    val expiresAt: LocalDateTime,
    @field:Schema(description = "이 묶음으로 1회가 차감됐는지 — 한 장이라도 결과가 저장되면 true(묶음당 최대 1회)")
    val usageRecorded: Boolean,
    @field:Schema(description = "결과 개정 번호 — 사진 결과가 저장될 때마다 1 씩 오른다")
    val resultRevision: Long,
    @field:Schema(description = "사진별 상태(선택 순서)")
    val photos: List<ScanBatchPhotoResponse>,
    @field:Schema(description = "모든 성공 사진의 메뉴를 합친 한 목록 — 원문 이름·가격·옵션이 모두 같은 행만 병합하고 출처를 전부 남긴다. 순서는 처음 나온 사진·행 순")
    val items: List<ScanBatchItemResponse>,
) {
    companion object {
        fun of(operation: ScanOperation, batchId: UUID, photos: List<ScanOperationPhoto>, now: LocalDateTime, items: List<ScanBatchItemResponse>): ScanBatchResponse {
            val lifecycle = when {
                operation.lifecycle == ScanBatchService.CANCELLED -> ScanBatchLifecycle.CANCELLED
                operation.lifecycle == ScanBatchService.QUOTA_EXHAUSTED -> ScanBatchLifecycle.QUOTA_EXHAUSTED
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
                items = items,
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
    @field:Schema(description = "같은 묶음에서 다시 시도할 수 있는지(추가 차감 없음, 사진당 시도 최대 3회)")
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
                retryable = batchActive && state == ScanPhotoState.FAILED && reason != ScanOperationPhoto.QUOTA_EXHAUSTED && photo.attemptNo < ScanOperationPhoto.MAX_ATTEMPTS,
            )
        }
    }
}

@Schema(description = "병합된 메뉴 한 행")
data class ScanBatchItemResponse(
    @field:Schema(description = "병합 행 id — 늦게 끝난 사진의 같은 메뉴가 붙어도 바뀌지 않는다(장바구니·주문 키)", example = "3f2b1c0e-8a9d-4e7f-b6a5-1c2d3e4f5a6b")
    val mergedItemId: String,
    @field:Schema(description = "메뉴판에 적힌 원문 표기", example = "불고기 (2인분)")
    val rawName: String,
    @field:Schema(description = "표준 한국어 이름", example = "불고기")
    val koreanName: String,
    @field:Schema(description = "표시 이름 — 카탈로그 음식과 매칭되면 묶음 언어로 번역, 아니면 한국어 이름", example = "Bulgogi")
    val name: String,
    @field:Schema(description = "가격(원) — 메뉴판에 없거나 읽지 못했으면 null", example = "15000", nullable = true)
    val priceKrw: Int?,
    @field:Schema(description = "메뉴판에 적힌 크기·옵션 표기 — 없으면 null", example = "2인분", nullable = true)
    val option: String?,
    @field:Schema(description = "매칭된 카탈로그 음식 id(정확 매칭만) — 없으면 null", nullable = true)
    val foodId: Long?,
    @field:Schema(description = "카탈로그 READY 음식과 정확히 매칭됐는지")
    val matched: Boolean,
    @field:Schema(description = "회원 회피 재료 기준 위험도 — 조회 시점의 회피 설정·음식으로 계산, 미매칭은 UNKNOWN")
    val riskLevel: String,
    @field:Schema(description = "이 행이 나온 사진·행 전부(선택 순서)")
    val sources: List<ScanBatchItemSourceResponse>,
) {
    companion object {
        fun of(item: ScanMergedItem, food: Food?, risk: RiskLevel?, language: LanguageCode, sources: List<Pair<ScanOperationPhoto, Int>>): ScanBatchItemResponse {
            val koreanName = item.document["koreanName"] as String
            return ScanBatchItemResponse(
                mergedItemId = ScanBatchService.uuidOf(item.publicId).toString(),
                rawName = item.document["rawName"] as String,
                koreanName = koreanName,
                name = food?.displayName(language) ?: koreanName,
                priceKrw = (item.document["priceKrw"] as Number?)?.toInt(),
                option = item.document["option"] as String?,
                foodId = food?.id,
                matched = food != null,
                riskLevel = (risk ?: RiskLevel.UNKNOWN).name,
                sources = sources.map { (photo, row) -> ScanBatchItemSourceResponse(photo.selectionIndex, ScanBatchService.uuidOf(photo.photoId).toString(), row) },
            )
        }
    }
}

@Schema(description = "병합 행의 출처 — 사진과 그 사진 안 행 번호")
data class ScanBatchItemSourceResponse(
    @field:Schema(description = "사진 선택 순서", example = "0")
    val index: Int,
    @field:Schema(description = "사진 id", example = "6a1f0e1c-2b3d-4c5e-8f90-0a1b2c3d4e5f")
    val photoId: String,
    @field:Schema(description = "그 사진에서 읽힌 행 순서(0부터)", example = "2")
    val rowIndex: Int,
)
