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
    @Query(nativeQuery = true, value = "select * from food_review where id = :id for update")
    fun findAnyByIdForUpdate(@Param("id") id: Long): Review?

    @Query(nativeQuery = true, value = "select * from food_review where id in (:ids)")
    fun findAllAnyStatusByIdIn(@Param("ids") ids: Collection<Long>): List<Review>

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
            SELECT t.id AS foodId, t.cnt AS reviewCount FROM (
              SELECT f.id, (SELECT COUNT(*) FROM food_review c WHERE c.food_id = f.id AND c.status = 'ACTIVE') AS cnt
              FROM food f
              WHERE f.status = 'ACTIVE' AND f.content_status = 'READY'
                AND NOT EXISTS (
                  SELECT 1 FROM food_review r JOIN member m ON m.id = r.member_id
                  WHERE r.food_id = f.id AND m.is_bot = 1 AND r.created_at >= :since
                )
                AND EXISTS (
                  SELECT 1 FROM member b
                  WHERE b.is_bot = 1 AND b.member_status = 'ACTIVE' AND b.status = 'ACTIVE'
                    AND NOT EXISTS (SELECT 1 FROM food_review br WHERE br.food_id = f.id AND br.member_id = b.id)
                )
            ) t
            WHERE t.cnt > :afterReviewCount OR (t.cnt = :afterReviewCount AND t.id > :afterFoodId)
            ORDER BY t.cnt, t.id
            LIMIT :limit
        """,
    )
    fun findReviewBotCandidatePage(
        @Param("since") since: LocalDateTime,
        @Param("afterReviewCount") afterReviewCount: Long,
        @Param("afterFoodId") afterFoodId: Long,
        @Param("limit") limit: Int,
    ): List<ReviewBotCandidate>

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
