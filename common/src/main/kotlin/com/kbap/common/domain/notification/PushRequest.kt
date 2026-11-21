package com.kbap.common.domain.notification

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.domain.notification.model.NotificationType

data class PushRequest(
    val type: NotificationType,
    val memberIds: Collection<Long>,
    val args: Map<String, String> = emptyMap(),
    val argsByLang: Map<LanguageCode, Map<String, String>> = emptyMap(),
    val data: Map<String, Any> = emptyMap(),
    val ttlSeconds: Int? = null,
    val mealSlot: MealSlot? = null,
    val guestInstallationIds: Collection<String> = emptyList(),
    /** 절대 URL 만. Expo richContent.image — Android 는 그대로, iOS 는 앱에 Notification Service Extension 이 있어야 보인다. */
    val imageUrl: String? = null,
)

data class PushContent(
    val title: String,
    val body: String,
)

data class PushEnvelope(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any>,
    val channelId: String,
    val ttlSeconds: Int? = null,
    val imageUrl: String? = null,
)

data class PreparedPush(
    val messages: List<PushEnvelope>,
    val dispatchIds: List<Long>,
) {
    fun isEmpty(): Boolean = messages.isEmpty()
}

data class PushOutcome(
    val ok: Boolean,
    val ticketId: String?,
    val error: String?,
)

data class PushDispatchResult(
    val sent: Int,
    val failed: Int,
)
