package com.kbap.common.domain.community.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(name = "community_post")
class Posting(
    @Column(name = "member_id", nullable = false)
    val memberId: Long,

    @Column(nullable = false, length = MAX_CONTENT_LENGTH)
    var content: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "image_refs")
    var imageRefs: List<String>? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "food_ids")
    var foodIds: List<Long>? = null,
) : BaseEntity() {
    // 사용자 수정만 기록한다 — updatedAt 은 관리자 조치 등 모든 변경에 움직여 "(edited)" 표시 판별에 못 쓴다.
    @Column(name = "edited_at")
    var editedAt: LocalDateTime? = null

    init {
        requireValid(content, imageRefs, foodIds)
    }

    fun update(content: String, imageRefs: List<String>?, foodIds: List<Long>?) {
        requireValid(content, imageRefs, foodIds)
        this.content = content
        this.imageRefs = imageRefs
        this.foodIds = foodIds
        this.editedAt = LocalDateTime.now()
    }

    fun isOwnedBy(memberId: Long): Boolean = this.memberId == memberId

    private fun requireValid(content: String, imageRefs: List<String>?, foodIds: List<Long>?) {
        require(content.isNotBlank()) { "본문은 비어 있을 수 없습니다" }
        require(content.length <= MAX_CONTENT_LENGTH) { "본문은 최대 ${MAX_CONTENT_LENGTH}자입니다" }
        require(imageRefs == null || imageRefs.size <= MAX_IMAGE_COUNT) { "사진은 최대 ${MAX_IMAGE_COUNT}장입니다" }
        require(foodIds == null || foodIds.size <= MAX_FOOD_TAG_COUNT) { "음식 태그는 최대 ${MAX_FOOD_TAG_COUNT}개입니다" }
        require(foodIds == null || foodIds.size == foodIds.toSet().size) { "같은 음식을 중복 태그할 수 없습니다" }
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 2000
        const val MAX_IMAGE_COUNT = 4
        const val MAX_FOOD_TAG_COUNT = 3
    }
}
