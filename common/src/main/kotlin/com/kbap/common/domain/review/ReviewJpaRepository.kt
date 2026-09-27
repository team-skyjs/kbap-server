package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface RatingAggregate {
    val average: Double?
    val reviewCount: Long
}

interface FoodRatingAggregate {
    val foodId: Long
    val average: Double?
    val reviewCount: Long
}

interface ReviewJpaRepository : JpaRepository<Review, Long>, ReviewRepositoryCustom {
    @Query(
        """
        select r from Review r
        where r.memberId = :memberId
          and (:cursor is null or r.id < :cursor)
        order by r.id desc
        """,
    )
    fun findMemberReviewPage(
        @Param("memberId") memberId: Long,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Review>

    @Query(
        """
        select avg(r.rating) as average, count(r) as reviewCount
        from Review r
        where r.foodId = :foodId
          and (:countryCode is null or r.authorCountryCode = :countryCode)
        """,
    )
    fun aggregateRating(
        @Param("foodId") foodId: Long,
        @Param("countryCode") countryCode: String?,
    ): RatingAggregate

    @Query(
        """
        select r.foodId as foodId, avg(r.rating) as average, count(r) as reviewCount
        from Review r
        where r.foodId in :foodIds
        group by r.foodId
        """,
    )
    fun aggregateRatingsByFoodIds(@Param("foodIds") foodIds: List<Long>): List<FoodRatingAggregate>

    fun countByMemberIdAndFoodId(memberId: Long, foodId: Long): Long

    fun findByMemberId(memberId: Long, pageable: Pageable): Page<Review>

    @Query(
        nativeQuery = true,
        value = """
            SELECT f.id FROM food f
            WHERE f.status = 'ACTIVE' AND f.content_status = 'READY'
              AND NOT EXISTS (
                SELECT 1 FROM food_review r JOIN member m ON m.id = r.member_id
                WHERE r.food_id = f.id AND m.is_bot = 1 AND r.created_at >= :since
              )
            ORDER BY (SELECT COUNT(*) FROM food_review c WHERE c.food_id = f.id AND c.status = 'ACTIVE'), RAND()
            LIMIT :limit
        """,
    )
    fun findReviewBotTargetFoodIds(@Param("since") since: LocalDateTime, @Param("limit") limit: Int): List<Long>

    @Query(
        nativeQuery = true,
        value = """
            SELECT COUNT(*) FROM food_review r JOIN member m ON m.id = r.member_id
            WHERE m.is_bot = 1 AND r.created_at >= :since
        """,
    )
    fun countReviewBotReviewsSince(@Param("since") since: LocalDateTime): Long

    @Query(
        nativeQuery = true,
        value = "SELECT DISTINCT r.member_id FROM food_review r WHERE r.food_id = :foodId AND r.member_id IN (:memberIds)",
    )
    fun findReviewerIdsOfFood(@Param("foodId") foodId: Long, @Param("memberIds") memberIds: Collection<Long>): List<Long>
}
