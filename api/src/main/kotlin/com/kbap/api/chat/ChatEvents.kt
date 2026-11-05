package com.kbap.api.chat

data class ChatUserMessageSent(
    val conversationId: Long,
    val messageId: Long,
    val memberId: Long?,
    val content: String,
    val imageCount: Int,
)

data class ChatOperatorMessageSent(
    val conversationId: Long,
    val messageId: Long,
    val memberId: Long?,
    val guestInstallationId: String?,
    val content: String,
)
