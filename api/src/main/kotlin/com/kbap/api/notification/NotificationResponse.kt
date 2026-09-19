package com.kbap.api.notification

import com.kbap.common.domain.notification.model.Notification
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZoneId

@Schema(description = "알림함 항목 — 목록 조회와 읽음 처리가 같은 스키마를 쓴다")
data class NotificationResponse(
    @field:Schema(description = "알림 id", example = "456")
    val id: Long,

    @field:Schema(
        description = "알림 유형 — 푸시 data.type 과 같은 어휘. HELPFUL·SCAN_SUGGESTION·REVIEW_REMINDER·NEWS·MEAL_TIME. 모르는 값은 이동 없이 처리한다",
        example = "REVIEW_REMINDER",
    )
    val type: String,

    @field:Schema(
        description = "대상 음식 id — REVIEW_REMINDER 이면 발송 data.foodId, 그 외 유형은 항상 null. REVIEW_REMINDER 라도 값이 없거나 정수가 아니면 null",
        example = "7",
        nullable = true,
    )
    val foodId: Long?,

    @field:Schema(description = "제목 — 발송 시점에 저장된 문자열 그대로", example = "식사는 어떠셨나요?")
    val title: String,

    @field:Schema(description = "본문 — 발송 시점에 저장된 문자열 그대로")
    val body: String,

    @field:Schema(description = "수신 시각(epoch 밀리초)", example = "1789540000000")
    val receivedAt: Long,

    @field:Schema(description = "읽음 여부 — false 는 새 알림", example = "false")
    val read: Boolean,
) {
    companion object {
        fun from(notification: Notification) = NotificationResponse(
            id = notification.id,
            type = notification.type.name,
            foodId = notification.foodIdOrNull(),
            title = notification.title,
            body = notification.body,
            receivedAt = notification.createdAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            read = notification.isRead(),
        )
    }
}
