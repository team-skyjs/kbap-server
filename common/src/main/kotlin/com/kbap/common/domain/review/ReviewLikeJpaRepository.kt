package com.kbap.common.domain.review

import com.kbap.common.domain.review.model.ReviewLike
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

interface ReviewLikeCount {
    val reviewId: Long
    val likeCount: Long
}

interface ReviewLikeJpaRepository : JpaRepository<ReviewLike, Long> {
    // 신규·중복·재등록(부활)을 한 문장으로 원자 처리 — 트랜잭션 안 save() 예외 폴백은 rollback-only 마킹으로 불가
    // @Modifying 쿼리는 CRUD 메서드와 달리 자동 트랜잭션이 없어 자체 선언(호출부 트랜잭션엔 REQUIRED 로 참여)
    @Transactional
    @Modifying
    @Query(
        value = """
            INSERT INTO review_like (review_id, member_id, status, created_at, updated_at)
            VALUES (:reviewId, :memberId, 'ACTIVE', NOW(6), NOW(6))
            ON DUPLICATE KEY UPDATE status = 'ACTIVE', updated_at = NOW(6)
        """,
        nativeQuery = true,
    )
    fun upsertActive(
        @Param("reviewId") reviewId: Long,
        @Param("memberId") memberId: Long,
    )

    fun findByReviewIdAndMemberId(reviewId: Long, memberId: Long): ReviewLike?

    @Query(
        """
        select rl.reviewId as reviewId, count(rl) as likeCount
        from ReviewLike rl
        where rl.reviewId in :reviewIds
        group by rl.reviewId
        """,
    )
    fun countByReviewIds(@Param("reviewIds") reviewIds: Collection<Long>): List<ReviewLikeCount>

    @Query(
        """
        select rl.reviewId
        from ReviewLike rl
        where rl.memberId = :memberId and rl.reviewId in :reviewIds
        """,
    )
    fun findLikedReviewIds(
        @Param("memberId") memberId: Long,
        @Param("reviewIds") reviewIds: Collection<Long>,
    ): List<Long>
}
