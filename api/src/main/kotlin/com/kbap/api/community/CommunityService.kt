package com.kbap.api.community

import com.kbap.api.image.UploadPurpose
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.community.PostingJpaRepository
import com.kbap.common.domain.community.model.Posting
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.image.UploadedImageJpaRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommunityService(
    private val postingRepository: PostingJpaRepository,
    private val foodService: FoodService,
    private val uploadedImageRepository: UploadedImageJpaRepository,
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
        if (imagePaths.isNullOrEmpty()) return
        val ownedCommunityPaths = uploadedImageRepository.findByPathIn(imagePaths)
            .filter { it.isOwnedBy(memberId) && it.path.contains(COMMUNITY_IMAGE_PATH_SEGMENT) }
            .map { it.path }
            .toSet()
        if (!ownedCommunityPaths.containsAll(imagePaths)) {
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
        val COMMUNITY_IMAGE_PATH_SEGMENT = "images/${UploadPurpose.COMMUNITY.prefix}/"
    }
}
