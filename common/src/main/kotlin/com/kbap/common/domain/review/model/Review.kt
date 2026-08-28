package com.kbap.common.domain.review.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes

@Entity
@Table(name = "food_review")
class Review(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(name = "food_id", nullable = false)
    val foodId: Long,

    @Column(nullable = false, columnDefinition = "TINYINT")
    var rating: Int,

    @Column(name = "serving_speed_rating", nullable = false, columnDefinition = "TINYINT")
    var servingSpeedRating: Int = 0,

    @Column(name = "staff_kindness_rating", nullable = false, columnDefinition = "TINYINT")
    var staffKindnessRating: Int = 0,

    @Column(length = MAX_CONTENT_LENGTH)
    var content: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_refs")
    var imageRefs: List<String>? = null,

    @Column(name = "author_country_code", length = 10)
    val authorCountryCode: String? = null,

    @Embedded
    var place: ReviewPlace? = null,
) : BaseEntity() {
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false, columnDefinition = "bigint not null default 0")
    var version: Long = 0

    init {
        requireValid(rating, servingSpeedRating, staffKindnessRating, content, imageRefs)
    }

    fun update(
        rating: Int,
        servingSpeedRating: Int,
        staffKindnessRating: Int,
        content: String?,
        imageRefs: List<String>?,
        place: ReviewPlace?,
    ) {
        requireValid(rating, servingSpeedRating, staffKindnessRating, content, imageRefs)
        this.rating = rating
        this.servingSpeedRating = servingSpeedRating
        this.staffKindnessRating = staffKindnessRating
        this.content = content
        this.imageRefs = imageRefs
        this.place = place
    }

    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId

    fun removeImages() {
        imageRefs = null
    }

    private fun requireValid(
        rating: Int,
        servingSpeedRating: Int,
        staffKindnessRating: Int,
        content: String?,
        imageRefs: List<String>?,
    ) {
        require(rating in RATING_RANGE) { "rating 은 1~5 여야 합니다: $rating" }
        require(servingSpeedRating in EXTRA_RATING_RANGE) { "servingSpeedRating 은 0~5 여야 합니다: $servingSpeedRating" }
        require(staffKindnessRating in EXTRA_RATING_RANGE) { "staffKindnessRating 은 0~5 여야 합니다: $staffKindnessRating" }
        require(content == null || content.length <= MAX_CONTENT_LENGTH) { "content 는 최대 ${MAX_CONTENT_LENGTH}자입니다" }
        require(imageRefs == null || imageRefs.size <= MAX_IMAGE_COUNT) { "사진은 최대 ${MAX_IMAGE_COUNT}장입니다" }
    }

    companion object {
        val RATING_RANGE = 1..5
        val EXTRA_RATING_RANGE = 0..5
        const val MAX_CONTENT_LENGTH = 1000
        const val MAX_IMAGE_COUNT = 3
    }
}
