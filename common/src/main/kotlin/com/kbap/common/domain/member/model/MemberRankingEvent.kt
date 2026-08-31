package com.kbap.common.domain.member.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

enum class RankingEventType {
    REVIEW_CREATED,
    REVIEW_DELETED,
}

@Entity
@Table(
    name = "member_ranking_event",
    uniqueConstraints = [UniqueConstraint(name = "uq_member_ranking_event", columnNames = ["review_id", "event"])],
)
class MemberRankingEvent(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "review_id", nullable = false)
    val reviewId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('REVIEW_CREATED','REVIEW_DELETED')")
    val event: RankingEventType,

    @Column(name = "review_count_delta", nullable = false, columnDefinition = "TINYINT")
    val reviewCountDelta: Int,

    @Column(name = "unique_food_count_delta", nullable = false, columnDefinition = "TINYINT")
    val uniqueFoodCountDelta: Int,
) : BaseEntity() {
    companion object {
        fun reviewCreated(memberId: Long, reviewId: Long, firstReviewOfFood: Boolean): MemberRankingEvent =
            MemberRankingEvent(
                memberId = memberId,
                reviewId = reviewId,
                event = RankingEventType.REVIEW_CREATED,
                reviewCountDelta = 1,
                uniqueFoodCountDelta = if (firstReviewOfFood) 1 else 0,
            )

        fun reviewDeleted(memberId: Long, reviewId: Long, lastReviewOfFood: Boolean): MemberRankingEvent =
            MemberRankingEvent(
                memberId = memberId,
                reviewId = reviewId,
                event = RankingEventType.REVIEW_DELETED,
                reviewCountDelta = -1,
                uniqueFoodCountDelta = if (lastReviewOfFood) -1 else 0,
            )
    }
}
