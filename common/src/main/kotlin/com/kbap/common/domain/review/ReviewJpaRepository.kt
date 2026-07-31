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

interface ReviewJpaRepository : JpaRepository<Review, Long> {
    @Query(
        """
        select r from Review r
        where r.foodId = :foodId
          and (:countryCode is null or r.authorCountryCode = :countryCode)
          and (:cursor is null or r.id < :cursor)
        order by r.id desc
        """,
    )
    fun findFoodReviewPage(
        @Param("foodId") foodId: Long,
        @Param("countryCode") countryCode: String?,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Review>

    // 빈 excludedIds 는 받지 않는다(빈 not in 은 SQL 이 깨짐) — 제외가 없으면 호출부가 기본 오버로드를 쓴다.
    @Query(
        """
        select r from Review r
        where r.foodId = :foodId
          and (:countryCode is null or r.authorCountryCode = :countryCode)
          and (:cursor is null or r.id < :cursor)
          and r.id not in :excludedIds
        order by r.id desc
        """,
    )
    fun findFoodReviewPage(
        @Param("foodId") foodId: Long,
        @Param("countryCode") countryCode: String?,
        @Param("cursor") cursor: Long?,
        @Param("excludedIds") excludedIds: List<Long>,
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

    fun countByMemberIdAndFoodId(memberId: Long, foodId: Long): Long
}
