package com.kbap.api.admin

import com.kbap.common.domain.food.model.RegenerationIntent
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

@Schema(description = "대표 이미지 재생성 요청")
data class AdminFoodImageRegenerateRequest(
    @field:Schema(
        description = """재생성 의도 — 생성이 **실패했을 때만** 결과가 갈린다(성공은 의도와 무관하게 PENDING_REVIEW).
REPLACE_BETTER: 지금 이미지도 쓸 만하지만 더 나은 걸로 교체. 실패하면 옛 이미지로 READY 복원. READY 음식에만 쓸 수 있다(아니면 409 FOOD-011).
WRONG_IMAGE: 지금 이미지가 잘못됨. 실패해도 숨긴 채 유지.
**누락 시 WRONG_IMAGE 와 동일하게 동작한다.** 기존 어드민이 이 값 없이 호출하므로 지금은 선택이며, 어드민 KB-621 배포 후 필수로 전환한다 — 그 전에 필수로 올리면 기존 어드민이 400 으로 깨진다.""",
        example = "REPLACE_BETTER",
    )
    val intent: RegenerationIntent? = null,

    @field:Size(max = 500, message = "reason 은 500자 이하여야 합니다")
    @field:Schema(description = "재생성 사유(선택, 500자 이하)", example = "배경이 어두워 음식이 잘 안 보임")
    val reason: String? = null,
)
