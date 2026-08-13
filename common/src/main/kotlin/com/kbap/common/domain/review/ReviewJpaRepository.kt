package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.Review
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface RatingAggregate {
    val average: Double?
    val reviewCount: Long
}

interface FoodRatingAggregate {
    val foodId: Long
    val average: Double?
    val reviewCount: Long
}

interface ReviewJpaRepository : JpaRepository<Review, Long> {
    @Query(
        """
        select r from Review r
        where (:foodId is null or r.foodId = :foodId)
          and (:countryCode is null or r.authorCountryCode = :countryCode)
          and (:cursor is null or r.id < :cursor)
          and r.memberId not in :excludedMemberIds
          and r.id not in :excludedReviewIds
          and exists (select 1 from Food f where f.id = r.foodId)
        order by r.id desc
        """,
    )
    fun findReviewPage(
        @Param("foodId") foodId: Long?,
        @Param("countryCode") countryCode: String?,
        @Param("cursor") cursor: Long?,
        @Param("excludedMemberIds") excludedMemberIds: List<Long>,
        @Param("excludedReviewIds") excludedReviewIds: List<Long>,
        pageable: Pageable,
    ): List<Review>

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
}
