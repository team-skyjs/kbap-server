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
        requireValid(rating, content, imageRefs)
    }

    fun update(rating: Int, content: String?, imageRefs: List<String>?, place: ReviewPlace?) {
        requireValid(rating, content, imageRefs)
        this.rating = rating
        this.content = content
        this.imageRefs = imageRefs
        this.place = place
    }

    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId

    private fun requireValid(rating: Int, content: String?, imageRefs: List<String>?) {
        require(rating in RATING_RANGE) { "rating 은 1~5 여야 합니다: $rating" }
        require(content == null || content.length <= MAX_CONTENT_LENGTH) { "content 는 최대 ${MAX_CONTENT_LENGTH}자입니다" }
        require(imageRefs == null || imageRefs.size <= MAX_IMAGE_COUNT) { "사진은 최대 ${MAX_IMAGE_COUNT}장입니다" }
    }

    companion object {
        val RATING_RANGE = 1..5
        const val MAX_CONTENT_LENGTH = 1000
        const val MAX_IMAGE_COUNT = 3
    }
}
