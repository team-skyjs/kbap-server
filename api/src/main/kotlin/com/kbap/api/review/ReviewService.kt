package com.kbap.api.review

import java.util.Optional
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.api.food.FoodService
import com.kbap.api.image.UploadedImageService
import com.kbap.common.domain.image.BotPhotoJpaRepository
import com.kbap.common.util.LikeWildcards
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.MemberRankingEventJpaRepository
import com.kbap.api.block.MemberBlockService
import com.kbap.api.member.MemberService
import com.kbap.common.domain.member.model.MemberRankingEvent
import com.kbap.common.domain.member.model.RankingEventType
import com.kbap.api.core.Page
import com.kbap.common.domain.review.RatingAggregate
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.ReviewSort
import com.kbap.common.domain.review.ReviewLikeJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewPlace
import com.kbap.common.domain.review.model.ReviewTargetType
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.api.processedfood.ProcessedFoodCandidateIndex
import com.kbap.api.processedfood.ProcessedFoodService
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.ReviewTranslationSource
import com.kbap.common.domain.translation.model.TranslationTargetType
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ReviewService(
    private val reviewRepository: ReviewJpaRepository,
    private val botPhotos: BotPhotoJpaRepository,
    @Value("\${kbap.storage.key-prefix:local}") private val storageKeyPrefix: String,
    private val reviewLikeRepository: ReviewLikeJpaRepository,
    private val foodService: FoodService,
    private val foodRepository: FoodJpaRepository,
    private val memberService: MemberService,
    private val uploadedImageService: UploadedImageService,
    private val rankingEventRepository: MemberRankingEventJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val memberBlockService: MemberBlockService,
    private val eventPublisher: ApplicationEventPublisher,
    private val languageDetector: ReviewLanguageDetector,
    private val processedFoods: ProcessedFoodProductJpaRepository,
    private val processedFoodService: ProcessedFoodService,
    private val processedFoodGroups: ProcessedFoodCandidateIndex,
    private val processedFoodReviewSwitch: ProcessedFoodReviewSwitch,
    private val translations: ContentTranslationJpaRepository,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
) {
    @Transactional
    fun createReview(
        memberId: Long,
        foodId: Long,
        rating: Int,
        servingSpeed: Int?,
        staffKindness: Int?,
        content: String?,
        imagePaths: List<String>?,
        place: ReviewPlace?,
    ): ReviewResponse {
        foodService.getReadyFood(foodId)
        verifyImageOwnership(memberId, imagePaths)
        return persistReview(memberId, foodId, rating, servingSpeed, staffKindness, content, imagePaths, place)
    }

    @Transactional
    fun createProcessedFoodReview(
        memberId: Long,
        processedFoodId: Long,
        rating: Int,
        content: String?,
        imagePaths: List<String>?,
        spiciness: Int? = null,
        sweetness: Int? = null,
    ): ReviewResponse {
        if (!processedFoodReviewSwitch.writeEnabled) throw BusinessException(ErrorCode.PROCESSED_FOOD_REVIEW_CLOSED)
        val product = processedFoodService.getAvailableProduct(processedFoodId)
        verifyImageOwnership(memberId, imagePaths)
        verifyContentLength(content)
        val authorCountryCode = memberService.getMember(memberId).profile.countryCode?.name
        if (memberRepository.increaseReviewCount(memberId) == 0) throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val review = reviewRepository.save(
            Review.forProcessedFood(memberId, product.id, rating, content, languageDetector.detect(content)?.code, imagePaths, authorCountryCode, spiciness, sweetness),
        )
        rankingEventRepository.save(MemberRankingEvent.reviewCreated(memberId, review.id, firstReviewOfFood = false))
        return ReviewResponse.from(review, imagePublicBaseUrl, authorOf(memberId), likeCount = 0, likedByMe = false, processedFood = ReviewProcessedFoodResponse.from(product))
    }

    @Transactional(readOnly = true)
    fun getProcessedFoodReviewPage(viewerMemberId: Long?, processedFoodId: Long, lang: LanguageCode, cursor: Long?): Page<ReviewResponse> {
        if (!processedFoods.existsById(processedFoodId)) throw BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND)
        val excluded = viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L)
        return toPage(reviewRepository.findProcessedFoodReviewPage(processedFoodGroups.groupMemberIds(processedFoodId), excluded, cursor, PAGE_SIZE + 1), viewerMemberId, lang)
    }

    @Transactional
    fun createBotReview(
        botId: Long,
        foodId: Long,
        rating: Int,
        content: String,
        imagePaths: List<String>,
        poolOwnerIds: Set<Long>,
    ): ReviewResponse {
        foodService.getReadyFood(foodId)
        if (!memberService.getMember(botId).isBot) throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val prefix = storageKeyPrefix.trim('/').let { if (it.isEmpty()) "images/review-bot/" else "$it/images/review-bot/" }
        val pattern = Regex(Regex.escape(prefix) + "[0-9a-f]{12}/[0-9]+\\.webp")
        if (imagePaths.isEmpty() || imagePaths.size > Review.MAX_IMAGE_COUNT || imagePaths.distinct().size != imagePaths.size || imagePaths.any { path ->
                !pattern.matches(path) || botPhotos.getRecords(LikeWildcards.escape(path)).singleOrNull()?.let {
                    it.path == path && it.status == "ACTIVE" && it.ownerKind == "ACTIVE_BOT" && botPhotos.hasOrdinaryReference(path) == 0L
                } != true
            }) throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        if (!uploadedImageService.areAllOwnedByAnyOf(imagePaths, UploadedImage.REVIEW_BOT_SEGMENT, poolOwnerIds)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
        return persistReview(botId, foodId, rating, null, null, content, imagePaths, null)
    }

    private fun persistReview(
        memberId: Long,
        foodId: Long,
        rating: Int,
        servingSpeed: Int?,
        staffKindness: Int?,
        content: String?,
        imagePaths: List<String>?,
        place: ReviewPlace?,
    ): ReviewResponse {
        verifyContentLength(content)
        val authorCountryCode = memberService.getMember(memberId).profile.countryCode?.name
        if (memberRepository.increaseReviewCount(memberId) == 0) throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        foodRepository.increaseReviewCount(foodId)

        val review = reviewRepository.save(
            Review(
                memberId = memberId,
                foodId = foodId,
                rating = rating,
                servingSpeedRating = servingSpeed ?: 0,
                staffKindnessRating = staffKindness ?: 0,
                content = content,
                language = languageDetector.detect(content)?.code,
                imageRefs = imagePaths,
                authorCountryCode = authorCountryCode,
                place = place,
            ),
        )

        val firstReviewOfFood = reviewRepository.countByMemberIdAndFoodId(memberId, foodId) == 1L
        if (firstReviewOfFood) {
            if (memberRepository.increaseUniqueReviewedFoodCount(memberId) == 0) throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        }
        rankingEventRepository.save(MemberRankingEvent.reviewCreated(memberId, review.id, firstReviewOfFood))
        return ReviewResponse.from(review, imagePublicBaseUrl, authorOf(memberId), likeCount = 0, likedByMe = false)
    }

    @Transactional
    fun updateReview(
        memberId: Long,
        reviewId: Long,
        rating: Int,
        servingSpeed: Int?,
        staffKindness: Int?,
        content: String?,
        imagePaths: List<String>?,
        place: ReviewPlace?,
        spiciness: Optional<Int>? = null,
        sweetness: Optional<Int>? = null,
    ): ReviewResponse {
        val review = getMyReview(memberId, reviewId)
        verifyContentLength(content)
        verifyImageOwnership(memberId, imagePaths)
        val forFood = review.targetType == ReviewTargetType.FOOD
        review.update(
            rating = rating,
            servingSpeedRating = if (forFood) servingSpeed ?: 0 else 0,
            staffKindnessRating = if (forFood) staffKindness ?: 0 else 0,
            spicinessRating = if (forFood) null else spiciness.keepingWhenAbsent(review.spicinessRating),
            sweetnessRating = if (forFood) null else sweetness.keepingWhenAbsent(review.sweetnessRating),
            content = content,
            language = languageDetector.detect(content)?.code,
            imageRefs = imagePaths,
            place = if (forFood) place else null,
        )
        val likeCount = reviewLikeRepository.countByReviewIds(listOf(review.id)).singleOrNull()?.likeCount ?: 0
        val likedByMe = reviewLikeRepository.findLikedReviewIds(memberId, listOf(review.id)).isNotEmpty()
        val processedFood = review.processedFoodId?.let { processedFoods.findById(it).orElse(null) }?.let(ReviewProcessedFoodResponse::from)
        return ReviewResponse.from(review, imagePublicBaseUrl, authorOf(memberId), likeCount, likedByMe, processedFood = processedFood)
    }

    @Transactional
    fun deleteReview(memberId: Long, reviewId: Long) {
        val review = getMyReviewForUpdate(memberId, reviewId)
        if (rankingEventRepository.existsByReviewIdAndEvent(review.id, RankingEventType.REVIEW_DELETED)) {
            throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        }
        softDelete(review)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteForModeration(review: Review) {
        if (!review.isActive()) return
        if (!memberRepository.existsById(review.memberId)) {
            review.delete()
            review.foodId?.let(foodRepository::decreaseReviewCount)
            return
        }
        softDelete(review)
    }

    private fun softDelete(review: Review) {
        val memberId = review.memberId
        val foodId = review.foodId
        review.delete()
        memberRepository.decreaseReviewCount(memberId)
        foodId?.let(foodRepository::decreaseReviewCount)
        val lastReviewOfFood = foodId != null && reviewRepository.countByMemberIdAndFoodId(memberId, foodId) == 0L
        if (lastReviewOfFood) memberRepository.decreaseUniqueReviewedFoodCount(memberId)
        rankingEventRepository.save(MemberRankingEvent.reviewDeleted(memberId, review.id, lastReviewOfFood))
    }

    @Transactional
    fun likeReview(memberId: Long, reviewId: Long) {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { BusinessException(ErrorCode.REVIEW_NOT_FOUND) }
        val existingLike = reviewLikeRepository.findByReviewIdAndMemberIdIncludingDeleted(reviewId, memberId)
        val now = LocalDateTime.now()
        reviewLikeRepository.upsertActive(reviewId = reviewId, memberId = memberId, now = now)
        val isNewLike = existingLike?.countsAsNewLikeAt(now) ?: true
        if (isNewLike && !review.isOwnedBy(memberId)) {
            eventPublisher.publishEvent(ReviewLiked(review.id, review.memberId, review.foodId, review.processedFoodId))
        }
    }

    @Transactional
    fun unlikeReview(memberId: Long, reviewId: Long) {
        reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId)?.delete()
    }

    @Transactional(readOnly = true)
    fun getReviewPage(
        viewerMemberId: Long?,
        foodId: Long?,
        countryCode: String?,
        lang: LanguageCode,
        sort: ReviewSort,
        descending: Boolean,
        minRating: Int?,
        maxRating: Int?,
        cursor: ReviewListCursor.Position?,
        includeProcessed: Boolean = false,
    ): ReviewListPage {
        foodId?.let { foodService.getReadyFood(it) }
        val rows = reviewRepository.findReviewPage(
            foodId = foodId,
            countryCode = countryCode,
            minRating = minRating,
            maxRating = maxRating,
            sort = sort,
            descending = descending,
            metricCursor = cursor?.metric,
            idCursor = cursor?.id,
            excludedMemberIds = viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L),
            excludedReviewIds = NO_EXCLUDED_REVIEW_IDS,
            limit = PAGE_SIZE + 1,
            includeProcessed = includeProcessed,
        )
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        val items = toResponses(page.map { it.review }, viewerMemberId, lang, includeFood = true)
        return ReviewListPage(
            items = if (cursor == null && foodId == null) withStoredTranslations(page.map { it.review }, items, lang) else items,
            hasNext = hasNext,
            nextCursor = page.lastOrNull()
                ?.takeIf { hasNext }
                ?.let { ReviewListCursor.encode(sort, it.metric, it.review.id) },
        )
    }

    private fun withStoredTranslations(reviews: List<Review>, items: List<ReviewResponse>, lang: LanguageCode): List<ReviewResponse> {
        val sourceHashes = reviews
            .filter { !it.content.isNullOrBlank() && it.language != lang.code }
            .associate { it.id to ReviewTranslationSource.hashOf(it.content!!) }
        if (sourceHashes.isEmpty()) return items
        val stored = translations.findAllByTargetTypeInAndTargetIdInAndLanguage(listOf(TranslationTargetType.REVIEW), sourceHashes.keys, lang.code)
            .filter { it.targetType == TranslationTargetType.REVIEW && it.sourceHash == sourceHashes[it.targetId] }
            .associateBy { it.targetId }
        return items.map { item -> stored[item.reviewId]?.let { item.copy(translation = ReviewTranslationResponse(it.translatedText, it.sourceLanguage)) } ?: item }
    }

    @Transactional(readOnly = true)
    fun getVisibleReview(viewerMemberId: Long?, reviewId: Long): Review {
        val review = reviewRepository.findById(reviewId).orElseThrow { BusinessException(ErrorCode.REVIEW_NOT_FOUND) }
        if (viewerMemberId != null && review.isOwnedBy(viewerMemberId)) return review
        val excluded = viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L)
        if (!reviewRepository.existsFeedVisible(reviewId, excluded, NO_EXCLUDED_REVIEW_IDS)) throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        return review
    }

    @Transactional(readOnly = true)
    fun getReview(viewerMemberId: Long?, reviewId: Long, lang: LanguageCode): ReviewResponse =
        toResponses(listOf(getVisibleReview(viewerMemberId, reviewId)), viewerMemberId, lang, includeFood = true).single()

    private fun excludedMemberIds(viewerMemberId: Long): List<Long> =
        memberBlockService.getBlockedMemberIds(viewerMemberId).ifEmpty { listOf(-1L) }

    @Transactional(readOnly = true)
    fun getMyReviewPage(memberId: Long, lang: LanguageCode, cursor: Long?, includeProcessed: Boolean = false): Page<ReviewResponse> =
        toPage(reviewRepository.findMemberReviewPage(memberId, includeProcessed, cursor, PageRequest.of(0, PAGE_SIZE + 1)), memberId, lang)

    @Transactional(readOnly = true)
    fun getFoodRatings(foodIds: List<Long>): Map<Long, FoodRating> {
        val distinctIds = foodIds.distinct()
        if (distinctIds.isEmpty()) return emptyMap()
        return reviewRepository.aggregateRatingsByFoodIds(distinctIds).associate {
            it.foodId to FoodRating(
                averageRating = (it.average ?: 0.0).roundToFirstDecimal(),
                reviewCount = it.reviewCount,
            )
        }
    }

    @Transactional(readOnly = true)
    fun getFoodRatingSummary(foodId: Long, viewerCountryCode: String?): RatingSummary {
        val overall = reviewRepository.aggregateRating(foodId, null)
        val sameCountry = viewerCountryCode?.let { reviewRepository.aggregateRating(foodId, it) }
        return RatingSummary(
            averageRating = overall.average?.roundToFirstDecimal(),
            reviewCount = overall.reviewCount,
            sameCountryAverageRating = sameCountry?.average?.roundToFirstDecimal(),
            sameCountryReviewCount = sameCountry?.reviewCount ?: 0,
            overallAxes = axesOf(overall),
            sameCountryAxes = sameCountry?.let(::axesOf) ?: RatingAxes.NONE,
        )
    }

    private fun axesOf(aggregate: RatingAggregate): RatingAxes =
        RatingAxes(
            servingSpeedAverage = aggregate.servingSpeedAverage?.roundToFirstDecimal(),
            servingSpeedCount = aggregate.servingSpeedCount,
            staffKindnessAverage = aggregate.staffKindnessAverage?.roundToFirstDecimal(),
            staffKindnessCount = aggregate.staffKindnessCount,
        )

    @Transactional(readOnly = true)
    fun getTopRatedFoods(size: Int): List<TopRatedFoodRating> =
        reviewRepository.findTopRatedFoods(size).map {
            val present = listOfNotNull(it.taste, it.servingSpeed, it.staffKindness)
            TopRatedFoodRating(
                foodId = it.foodId,
                reviewCount = it.reviewCount,
                taste = it.taste.roundToFirstDecimal(),
                servingSpeed = it.servingSpeed?.roundToFirstDecimal(),
                staffKindness = it.staffKindness?.roundToFirstDecimal(),
                overallRating = present.average().roundToFirstDecimal(),
            )
        }

    private fun Double.roundToFirstDecimal(): Double = Math.round(this * 10) / 10.0

    private fun Optional<Int>?.keepingWhenAbsent(current: Int?): Int? = if (this == null) current else orElse(null)

    @Transactional(readOnly = true)
    fun getRecentFoodReviews(foodId: Long, viewerMemberId: Long?, lang: LanguageCode): List<ReviewResponse> =
        toResponses(
            reviewRepository.findReviewPage(
                foodId = foodId,
                countryCode = null,
                minRating = null,
                maxRating = null,
                sort = ReviewSort.LATEST,
                descending = true,
                metricCursor = null,
                idCursor = null,
                excludedMemberIds = viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L),
                excludedReviewIds = NO_EXCLUDED_REVIEW_IDS,
                limit = RECENT_REVIEWS_SIZE,
            ).map { it.review },
            viewerMemberId,
            lang,
            includeFood = false,
        )

    private fun toPage(rows: List<Review>, viewerMemberId: Long?, lang: LanguageCode): Page<ReviewResponse> {
        if (rows.isEmpty()) {
            return Page(items = emptyList(), hasNext = false, nextCursor = null)
        }
        val hasNext = rows.size > PAGE_SIZE
        val page = rows.take(PAGE_SIZE)
        return Page(
            items = toResponses(page, viewerMemberId, lang, includeFood = true),
            hasNext = hasNext,
            nextCursor = if (hasNext) page.last().id else null,
        )
    }

    private fun toResponses(
        page: List<Review>,
        viewerMemberId: Long?,
        lang: LanguageCode,
        includeFood: Boolean,
    ): List<ReviewResponse> {
        if (page.isEmpty()) return emptyList()
        val authorsByMemberId = memberRepository.findAllById(page.map { it.memberId }.toSet())
            .associate { it.id to ReviewAuthorResponse.from(it, imagePublicBaseUrl) }
        val foodsByFoodId = if (includeFood) {
            foodRepository.findAllById(page.mapNotNull { it.foodId }.toSet())
                .associate { it.id to ReviewFoodResponse.from(it, lang, imagePublicBaseUrl) }
        } else {
            emptyMap()
        }
        val productsById = processedFoods.findAllById(page.mapNotNull { it.processedFoodId }.toSet())
            .associate { it.id to ReviewProcessedFoodResponse.from(it) }
        val reviewIds = page.map { it.id }
        val likeCountsByReviewId = reviewLikeRepository.countByReviewIds(reviewIds)
            .associate { it.reviewId to it.likeCount }
        val likedReviewIds = viewerMemberId
            ?.let { reviewLikeRepository.findLikedReviewIds(it, reviewIds).toSet() }
            .orEmpty()
        return page.map {
            ReviewResponse.from(
                it,
                imagePublicBaseUrl,
                authorsByMemberId[it.memberId],
                likeCount = likeCountsByReviewId[it.id] ?: 0,
                likedByMe = it.id in likedReviewIds,
                food = foodsByFoodId[it.foodId],
                processedFood = it.processedFoodId?.let(productsById::get),
            )
        }
    }

    private fun authorOf(memberId: Long): ReviewAuthorResponse =
        ReviewAuthorResponse.from(memberService.getMember(memberId), imagePublicBaseUrl)

    private fun getMyReview(memberId: Long, reviewId: Long): Review =
        ownedBy(memberId, reviewRepository.findById(reviewId).orElse(null))

    private fun getMyReviewForUpdate(memberId: Long, reviewId: Long): Review =
        ownedBy(memberId, reviewRepository.findByIdForUpdate(reviewId))

    private fun ownedBy(memberId: Long, review: Review?): Review {
        if (review == null) throw BusinessException(ErrorCode.REVIEW_NOT_FOUND)
        if (!review.isOwnedBy(memberId)) {
            throw BusinessException(ErrorCode.REVIEW_FORBIDDEN)
        }
        return review
    }

    private fun verifyContentLength(content: String?) {
        val body = content.orEmpty().trim()
        if (body.codePointCount(0, body.length) < Review.MIN_CONTENT_LENGTH) {
            throw BusinessException(ErrorCode.REVIEW_BODY_TOO_SHORT, mapOf("minLength" to Review.MIN_CONTENT_LENGTH))
        }
    }

    private fun verifyImageOwnership(memberId: Long, imagePaths: List<String>?) {
        if (!uploadedImageService.ownsAllImages(memberId, imagePaths, UploadPurpose.REVIEW)) {
            throw BusinessException(ErrorCode.REVIEW_IMAGE_NOT_VERIFIED)
        }
    }

    companion object {
        private val NO_EXCLUDED_REVIEW_IDS = listOf(-1L)

        const val PAGE_SIZE = 50
        const val RECENT_REVIEWS_SIZE = 5
    }
}

data class FoodRating(
    val averageRating: Double,
    val reviewCount: Long,
)

data class TopRatedFoodRating(
    val foodId: Long,
    val reviewCount: Long,
    val taste: Double,
    val servingSpeed: Double?,
    val staffKindness: Double?,
    val overallRating: Double,
)
