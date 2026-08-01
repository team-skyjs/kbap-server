package com.kbap.common.domain.review.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "review_like",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_review_like_pair", columnNames = ["review_id", "member_id"]),
    ],
)
class ReviewLike(
    @Column(nullable = false)
    val reviewId: Long = 0,

    @Column(nullable = false)
    val memberId: Long = 0,
) : BaseEntity()
