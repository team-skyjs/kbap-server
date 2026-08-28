package com.kbap.api.admin

import java.time.LocalDateTime

data class AdminPostResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val content: String,
    val imageUrls: List<String>,
    val foodIds: List<Long>,
    val commentCount: Long,
    val reportCount: Long,
    val editedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
)

data class AdminPostPageResponse(
    val items: List<AdminPostResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminCommentResponse(
    val id: Long,
    val memberId: Long,
    val memberNickname: String?,
    val content: String,
    val deleted: Boolean,
    val reportCount: Long,
    val editedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val replies: List<AdminCommentResponse>,
)

data class AdminCommentTreeResponse(
    val postId: Long,
    val totalCount: Int,
    val comments: List<AdminCommentResponse>,
)

data class AdminContentDeleteResponse(
    val id: Long,
    val deleted: Boolean,
)
