package com.kbap.api.community

import com.kbap.api.core.Page
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.community.CommentJpaRepository
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.community.model.Comment
import com.kbap.common.domain.community.model.Posting
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageService
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommunityService(
    private val postingRepository: PostingJpaRepository,
    private val commentRepository: CommentJpaRepository,
    private val foodService: FoodService,
    private val uploadedImageService: UploadedImageService,
    private val memberRepository: MemberJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun createPosting(
        memberId: Long,
        content: String,
        imagePaths: List<String>?,
        foodIds: List<Long>?,
    ): PostingResponse {
        verifyImageOwnership(memberId, imagePaths)
        verifyFoodTags(foodIds)

        val posting = postingRepository.save(
            Posting(
                memberId = memberId,
                content = content,
                imageRefs = imagePaths,
                foodIds = foodIds,
            ),
        )
        return PostingResponse.from(posting, imagePublicBaseUrl)
    }

    @Transactional
    fun updatePosting(
        memberId: Long,
        postId: Long,
        content: String,
        imagePaths: List<String>?,
        foodIds: List<Long>?,
    ): PostingResponse {
        val posting = getMyPosting(memberId, postId)
        verifyImageOwnership(memberId, imagePaths)
        verifyFoodTags(foodIds)

        posting.update(content = content, imageRefs = imagePaths, foodIds = foodIds)
        return PostingResponse.from(posting, imagePublicBaseUrl)
    }

    @Transactional
    fun deletePosting(memberId: Long, postId: Long) {
        getMyPosting(memberId, postId).delete()
    }

    @Transactional(readOnly = true)
    fun getPostingPage(viewerMemberId: Long?, cursor: Long?, lang: LanguageCode): Page<PostingItemResponse> {
        verifyGuestPageAccess(viewerMemberId, cursor)
        val rows = postingRepository.findPage(cursor, PageRequest.of(0, PAGE_SIZE + 1))
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        return Page(
            items = assemble(page, lang),
            hasNext = hasNext,
            nextCursor = if (hasNext) page.last().id else null,
        )
    }

    @Transactional(readOnly = true)
    fun getPosting(postId: Long, lang: LanguageCode): PostingItemResponse {
        return assemble(listOf(getVisiblePosting(postId)), lang).first()
    }

    @Transactional(readOnly = true)
    fun getCommentPage(postId: Long, cursor: Long?): Page<CommentItemResponse> {
        getVisiblePosting(postId)
        val rows = commentRepository.findTopLevelPage(postId, cursor, PageRequest.of(0, PAGE_SIZE + 1))
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        val repliesByParentId = if (page.isEmpty()) {
            emptyMap()
        } else {
            commentRepository.findByParentIdInOrderByIdAsc(page.map { it.id }).groupBy { it.parentId!! }
        }
        val authorsById = memberRepository
            .findAllById((page + repliesByParentId.values.flatten()).map { it.memberId }.toSet())
            .associateBy { it.id }

        fun authorOf(memberId: Long): CommentAuthorResponse =
            authorsById[memberId]?.let {
                CommentAuthorResponse(
                    memberId = it.id,
                    nickname = it.profile.nickname,
                    profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, it.profile.profileImageUrl),
                )
            } ?: WITHDRAWN_AUTHOR

        return Page(
            items = page.map { comment ->
                CommentItemResponse(
                    commentId = comment.id,
                    author = authorOf(comment.memberId),
                    content = comment.content,
                    createdAt = comment.createdAt,
                    replies = repliesByParentId[comment.id].orEmpty().map { reply ->
                        CommentReplyResponse(
                            commentId = reply.id,
                            author = authorOf(reply.memberId),
                            content = reply.content,
                            createdAt = reply.createdAt,
                        )
                    },
                )
            },
            hasNext = hasNext,
            nextCursor = if (hasNext) page.last().id else null,
        )
    }

    @Transactional
    fun createComment(memberId: Long, postId: Long, content: String, parentCommentId: Long?): CommentResponse {
        getVisiblePosting(postId)
        val comment = commentRepository.save(
            Comment(
                postId = postId,
                memberId = memberId,
                content = content,
                parentId = parentCommentId?.let { resolveTopLevelParentId(postId, it) },
            ),
        )
        return CommentResponse.from(comment)
    }

    @Transactional
    fun updateComment(memberId: Long, commentId: Long, content: String): CommentResponse {
        val comment = getMyComment(memberId, commentId)
        comment.update(content)
        return CommentResponse.from(comment)
    }

    @Transactional
    fun deleteComment(memberId: Long, commentId: Long) {
        val comment = getMyComment(memberId, commentId)
        comment.delete()
        if (!comment.isReply) {
            commentRepository.softDeleteReplies(comment.id)
        }
    }

    private fun resolveTopLevelParentId(postId: Long, parentCommentId: Long): Long {
        val parent = commentRepository.findById(parentCommentId).orElseThrow {
            BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND)
        }
        if (parent.postId != postId) {
            throw BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND)
        }
        return parent.parentId ?: parent.id
    }

    private fun getMyComment(memberId: Long, commentId: Long): Comment {
        val comment = commentRepository.findById(commentId).orElseThrow {
            BusinessException(ErrorCode.COMMUNITY_COMMENT_NOT_FOUND)
        }
        if (!comment.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.COMMUNITY_COMMENT_FORBIDDEN)
        }
        return comment
    }

    private fun getVisiblePosting(postId: Long): Posting {
        val posting = postingRepository.findById(postId).orElseThrow {
            BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND)
        }
        // 탈퇴 작성자의 글은 피드와 동일하게 존재 자체를 숨긴다
        if (!memberRepository.existsById(posting.memberId)) {
            throw BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND)
        }
        return posting
    }

    // 게스트는 첫 페이지만 — 커서가 있다는 것 자체가 두 번째 페이지 이후 요청이다.
    private fun verifyGuestPageAccess(viewerMemberId: Long?, cursor: Long?) {
        if (viewerMemberId == null && cursor != null) {
            throw BusinessException(ErrorCode.COMMUNITY_LOGIN_REQUIRED)
        }
    }

    // 피드·상세 공용 단일 조립 지점 — 차단 필터·신고 숨김·번역 후속 태스크는 여기만 고친다.
    private fun assemble(postings: List<Posting>, lang: LanguageCode): List<PostingItemResponse> {
        if (postings.isEmpty()) return emptyList()
        val authorsById = memberRepository.findAllById(postings.map { it.memberId }.toSet()).associateBy { it.id }
        val taggedFoodIds = postings.flatMap { it.foodIds.orEmpty() }.distinct()
        val foodsById = foodService.getReadyFoodsByIds(taggedFoodIds).associateBy { it.id }
        val commentCountByPostId = commentRepository.countByPostIds(postings.map { it.id })
            .associate { it.postId to it.commentCount }
        return postings.map { posting ->
            PostingItemResponse(
                postId = posting.id,
                author = authorOf(authorsById.getValue(posting.memberId)),
                content = posting.content,
                imageUrls = posting.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                foodTags = posting.foodIds.orEmpty().mapNotNull { foodId ->
                    foodsById[foodId]?.let { PostingFoodTagResponse(foodId = foodId, name = it.displayName(lang)) }
                },
                likeCount = 0,
                dislikeCount = 0,
                commentCount = (commentCountByPostId[posting.id] ?: 0L).toInt(),
                createdAt = posting.createdAt,
            )
        }
    }

    private fun authorOf(member: Member): PostingAuthorResponse =
        PostingAuthorResponse(
            memberId = member.id,
            nickname = member.profile.nickname,
            profileImageUrl = ImageUrls.resolve(imagePublicBaseUrl, member.profile.profileImageUrl),
        )

    private fun getMyPosting(memberId: Long, postId: Long): Posting {
        val posting = postingRepository.findById(postId).orElseThrow {
            BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND)
        }
        if (!posting.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.COMMUNITY_POSTING_FORBIDDEN)
        }
        return posting
    }

    private fun verifyImageOwnership(memberId: Long, imagePaths: List<String>?) {
        if (!uploadedImageService.ownsAllImages(memberId, imagePaths, UploadPurpose.COMMUNITY)) {
            throw BusinessException(ErrorCode.COMMUNITY_IMAGE_NOT_VERIFIED)
        }
    }

    private fun verifyFoodTags(foodIds: List<Long>?) {
        if (foodIds.isNullOrEmpty()) return
        if (foodIds.size != foodIds.toSet().size) {
            throw BusinessException(ErrorCode.COMMUNITY_FOOD_TAG_INVALID)
        }
        if (foodService.getReadyFoodsByIds(foodIds).size != foodIds.size) {
            throw BusinessException(ErrorCode.COMMUNITY_FOOD_TAG_INVALID)
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        private val WITHDRAWN_AUTHOR =
            CommentAuthorResponse(memberId = null, nickname = "(삭제)", profileImageUrl = null)
    }
}
