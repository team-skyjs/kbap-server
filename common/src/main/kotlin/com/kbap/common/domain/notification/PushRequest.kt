package com.kbap.common.domain.notification

import com.kbap.common.domain.notification.model.NotificationType

data class PushRequest(
    val type: NotificationType,
    val memberIds: Collection<Long>,
    val args: Map<String, String> = emptyMap(),
    val data: Map<String, Any> = emptyMap(),
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
