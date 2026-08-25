package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.community.CommentJpaRepository
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.community.model.Comment
import com.kbap.common.domain.community.model.Posting
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.report.ReportJpaRepository
import com.kbap.common.domain.report.model.ReportTargetType
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminCommunityService(
    private val postingRepository: PostingJpaRepository,
    private val commentRepository: CommentJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val reportRepository: ReportJpaRepository,
    private val auditRecorder: AdminAuditRecorder,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional(readOnly = true)
    fun getPostPage(q: String?, memberId: Long?, page: Int, size: Int): AdminPostPageResponse {
        val keyword = q?.trim()?.takeIf { it.isNotEmpty() }?.let { "%$it%" }
        val result = postingRepository.findAdminPage(memberId, keyword, PageRequest.of(page - 1, size))
        return AdminPostPageResponse(
            items = toPostResponses(result.content),
            page = page,
            size = size,
            totalCount = result.totalElements,
            totalPages = totalPagesOf(result.totalElements, size),
        )
    }

    @Transactional(readOnly = true)
    fun getComments(postId: Long): AdminCommentTreeResponse {
        if (!postingRepository.existsById(postId)) throw BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND)
        val comments = commentRepository.findAllByPostIdIncludingDeleted(postId)
        val nicknames = memberRepository.findAllById(comments.map { it.memberId }.toSet()).associate { it.id to it.nickname }
        val reportCounts = if (comments.isEmpty()) emptyMap() else
            reportRepository.countByTarget(ReportTargetType.COMMENT, comments.map { it.id }).associate { it.targetId to it.reportCount }
        val repliesByParent = comments.filter { it.isReply }.groupBy { it.parentId!! }
        fun toResponse(comment: Comment): AdminCommentResponse =
            AdminCommentResponse(
                id = comment.id,
                memberId = comment.memberId,
                memberNickname = nicknames[comment.memberId],
                content = comment.content,
                deleted = comment.isDeleted(),
                reportCount = reportCounts[comment.id] ?: 0L,
                editedAt = comment.editedAt,
                createdAt = comment.createdAt,
                replies = repliesByParent[comment.id].orEmpty().map { toResponse(it) },
            )
        return AdminCommentTreeResponse(
            postId = postId,
            totalCount = comments.size,
            comments = comments.filter { !it.isReply }.map { toResponse(it) },
        )
    }

    @Transactional
    fun deletePost(adminId: Long, postId: Long): AdminContentDeleteResponse {
        val posting = postingRepository.findById(postId).orElseThrow { BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND) }
        posting.delete()
        auditRecorder.record(adminId, AdminAuditAction.POST_DELETE, AdminAuditTargetType.POST, posting.id, mapOf("deleted" to false), mapOf("deleted" to true))
        return AdminContentDeleteResponse(id = posting.id, deleted = true)
    }

    @Transactional
    fun deleteComment(adminId: Long, commentId: Long): AdminContentDeleteResponse {
        val comment = commentRepository.findById(commentId).orElseThrow { BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND) }
        comment.delete()
        if (!comment.isReply) commentRepository.softDeleteReplies(comment.id)
        auditRecorder.record(adminId, AdminAuditAction.COMMENT_DELETE, AdminAuditTargetType.COMMENT, comment.id, mapOf("deleted" to false), mapOf("deleted" to true))
        return AdminContentDeleteResponse(id = comment.id, deleted = true)
    }

    private fun toPostResponses(postings: List<Posting>): List<AdminPostResponse> {
        if (postings.isEmpty()) return emptyList()
        val ids = postings.map { it.id }
        val nicknames = memberRepository.findAllById(postings.map { it.memberId }.toSet()).associate { it.id to it.nickname }
        val commentCounts = commentRepository.countByPostIds(ids).associate { it.postId to it.commentCount }
        val reportCounts = reportRepository.countByTarget(ReportTargetType.POST, ids).associate { it.targetId to it.reportCount }
        return postings.map {
            AdminPostResponse(
                id = it.id,
                memberId = it.memberId,
                memberNickname = nicknames[it.memberId],
                content = it.content,
                imageUrls = it.imageRefs.orEmpty().mapNotNull { ref -> ImageUrls.resolve(imagePublicBaseUrl, ref) },
                foodIds = it.foodIds.orEmpty(),
                commentCount = commentCounts[it.id] ?: 0L,
                reportCount = reportCounts[it.id] ?: 0L,
                editedAt = it.editedAt,
                createdAt = it.createdAt,
            )
        }
    }
}
