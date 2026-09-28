package com.kbap.api.admin

import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class RegenerationStateResolver(
    private val itemRepository: ImageBatchItemJpaRepository,
) {
    @Transactional(readOnly = true)
    fun of(foodId: Long): AdminRegenerationStateResponse? =
        itemRepository.findTopByFoodIdOrderByIdDesc(foodId)?.let(::stateOf)

    private fun stateOf(item: ImageBatchItem): AdminRegenerationStateResponse? {
        val state = when (item.itemStatus) {
            ImageBatchItemStatus.PENDING -> AdminRegenerationStateResponse.State.IN_PROGRESS
            ImageBatchItemStatus.FAILED -> AdminRegenerationStateResponse.State.FAILED
            ImageBatchItemStatus.DONE -> return null
        }
        return AdminRegenerationStateResponse(
            state = state,
            intent = item.regenerationIntent?.name,
            reason = item.regenerationReason,
            at = item.updatedAt,
        )
    }
}

@Schema(description = "마지막 이미지 생성 배치 항목 기준의 재생성 상태. 이력이 없거나 마지막이 성공(DONE)이면 null")
data class AdminRegenerationStateResponse(
    @field:Schema(description = "IN_PROGRESS = 배치 진행 중(음식은 PENDING_IMAGE 로 숨김) · FAILED = 마지막 생성 실패", example = "FAILED")
    val state: State,
    @field:Schema(
        description = "제출 시 기록한 의도. REPLACE_BETTER 실패는 READY 로 복원됐어도 여기 남는다 · WRONG_IMAGE 실패는 숨김 유지 · " +
            "null 은 의도 없이 제출(구 어드민·일괄 제출·신규 음식 첫 생성)",
        example = "WRONG_IMAGE",
        nullable = true,
    )
    val intent: String?,
    @field:Schema(description = "제출 시 남긴 사유", example = "배경이 어두움", nullable = true)
    val reason: String?,
    @field:Schema(description = "상태가 마지막으로 바뀐 시각(제출 또는 실패 판정)", example = "2026-09-22T22:50:00")
    val at: LocalDateTime,
) {
    enum class State { IN_PROGRESS, FAILED }
}
