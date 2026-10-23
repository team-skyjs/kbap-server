package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate

@Entity
@Table(
    name = "food_idol_video",
    uniqueConstraints = [UniqueConstraint(name = "uq_food_idol_video_food_position", columnNames = ["food_id", "position"])],
)
class FoodIdolVideo(
    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('YOUTUBE','TIKTOK')")
    var platform: IdolVideoPlatform = IdolVideoPlatform.YOUTUBE,

    @Column(name = "video_url", nullable = false, length = 500)
    var videoUrl: String = "",

    @Column(name = "published_month", nullable = false)
    var publishedMonth: LocalDate = LocalDate.MIN,

    @Column(nullable = false, columnDefinition = "TINYINT")
    var hype: Int = 1,

    @Column(nullable = false)
    var position: Int = 1,
) : BaseEntity()

enum class IdolVideoPlatform { YOUTUBE, TIKTOK }
