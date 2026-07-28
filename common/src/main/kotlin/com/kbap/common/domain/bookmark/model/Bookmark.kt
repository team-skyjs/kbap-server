package com.kbap.common.domain.bookmark.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "bookmark")
class Bookmark(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,
) : BaseEntity()
