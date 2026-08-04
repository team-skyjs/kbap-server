package com.kbap.api.community

import com.kbap.api.core.Page
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.community.PostingJpaRepository
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
    ): CommunityPostingResponse {
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
        return CommunityPostingResponse.from(posting, imagePublicBaseUrl)
    }

    @Transactional
    fun updatePosting(
        memberId: Long,
        postId: Long,
        content: String,
        imagePaths: List<String>?,
        foodIds: List<Long>?,
    ): CommunityPostingResponse {
        val posting = getMyPosting(memberId, postId)
        verifyImageOwnership(memberId, imagePaths)
        verifyFoodTags(foodIds)

        posting.update(content = content, imageRefs = imagePaths, foodIds = foodIds)
        return CommunityPostingResponse.from(posting, imagePublicBaseUrl)
    }

    @Transactional
    fun deletePosting(memberId: Long, postId: Long) {
        getMyPosting(memberId, postId).delete()
    }

    @Transactional(readOnly = true)
    fun getPostingPage(viewerMemberId: Long?, cursor: Long?, lang: LanguageCode): Page<CommunityPostingItemResponse> {
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
    fun getPosting(postId: Long, lang: LanguageCode): CommunityPostingItemResponse {
        val posting = postingRepository.findById(postId).orElseThrow {
            BusinessException(ErrorCode.COMMUNITY_POSTING_NOT_FOUND)
        }
        return assemble(listOf(posting), lang).first()
    }

    // 커서보다 최신인 글 수 = 이미 소비한 페이지 분량. LIMIT 프로젝션이라 깊은 커서에도 스캔이 21행에서 멈춘다.
    private fun verifyGuestPageAccess(viewerMemberId: Long?, cursor: Long?) {
        if (viewerMemberId != null || cursor == null) return
        if (postingRepository.findIdsFrom(cursor, PageRequest.of(0, PAGE_SIZE + 1)).size > PAGE_SIZE) {
            throw BusinessException(ErrorCode.COMMUNITY_LOGIN_REQUIRED)
        }
    }

    // 피드·상세 공용 단일 조립 지점 — 차단 필터·신고 숨김·번역 후속 태스크는 여기만 고친다.
    private fun assemble(postings: List<Posting>, lang: LanguageCode): List<CommunityPostingItemResponse> {
        if (postings.isEmpty()) return emptyList()
        val authorsById = memberRepository.findAllById(postings.map { it.memberId }.toSet()).associateBy { it.id }
        val taggedFoodIds = postings.flatMap { it.foodIds.orEmpty() }.distinct()
        val foodsById = foodService.getReadyFoodsByIds(taggedFoodIds).associateBy { it.id }
        return postings.map { posting ->
            CommunityPostingItemResponse(
                postId = posting.id,
                author = authorsById[posting.memberId]?.let { authorOf(it) } ?: WITHDRAWN_AUTHOR,
                content = posting.content,
                imageUrls = posting.imageRefs.orEmpty().mapNotNull { ImageUrls.resolve(imagePublicBaseUrl, it) },
                foodTags = posting.foodIds.orEmpty().mapNotNull { foodId ->
                    foodsById[foodId]?.let { CommunityFoodTagResponse(foodId = foodId, name = it.displayName(lang)) }
                },
                likeCount = 0,
                dislikeCount = 0,
                commentCount = 0,
                createdAt = posting.createdAt,
            )
        }
    }

    private fun authorOf(member: Member): CommunityAuthorResponse =
        CommunityAuthorResponse(
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

        private val WITHDRAWN_AUTHOR = CommunityAuthorResponse(
            memberId = null,
            nickname = WITHDRAWN_AUTHOR_NICKNAME,
            profileImageUrl = null,
        )

        const val WITHDRAWN_AUTHOR_NICKNAME = "탈퇴한 사용자"
    }
}
