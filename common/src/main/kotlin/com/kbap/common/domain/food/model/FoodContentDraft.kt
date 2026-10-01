package com.kbap.common.domain.food.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

@Entity
@Table(
    name = "food_content_draft",
    indexes = [
        Index(name = "idx_food_content_draft_food_status", columnList = "food_id, review_status"),
        Index(name = "idx_food_content_draft_status_id", columnList = "review_status, id"),
    ],
)
class FoodContentDraft(
    @Column(name = "food_id", nullable = false)
    val foodId: Long = 0,

    @Column(name = "outbox_id", nullable = false)
    val outboxId: Long = 0,

    @Column(name = "description", nullable = false, length = 255)
    val description: String = "",

    @Column(name = "long_description", length = Food.MAX_LONG_DESCRIPTION_LENGTH)
    val longDescription: String? = null,

    @Column(name = "spiciness", nullable = false)
    val spiciness: Int = 0,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "name_translations", nullable = false)
    val nameTranslations: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "description_translations", nullable = false)
    val descriptionTranslations: Map<String, String> = emptyMap(),

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ingredients")
    val ingredients: List<FoodIngredient>? = null,
) : BaseEntity() {
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    var reviewStatus: FoodContentDraftStatus = FoodContentDraftStatus.PENDING
        protected set

    @Column(name = "resolved_by")
    var resolvedBy: Long? = null
        protected set

    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null
        protected set

    @Column(name = "reject_reason", length = MAX_REJECT_REASON_LENGTH)
    var rejectReason: String? = null
        protected set

    init {
        require(spiciness in Food.SPICINESS_UNASSESSED..Food.MAX_SPICINESS) { "draft.spiciness 범위 밖: $spiciness" }
    }

    fun isPending(): Boolean = reviewStatus == FoodContentDraftStatus.PENDING

    fun supersede() {
        check(isPending()) { "대체할 수 있는 건 검수 대기 초안뿐입니다: $reviewStatus" }
        reviewStatus = FoodContentDraftStatus.SUPERSEDED
    }

    fun approve(adminAccountId: Long, at: LocalDateTime) = resolve(FoodContentDraftStatus.APPROVED, adminAccountId, at, null)

    fun reject(adminAccountId: Long, at: LocalDateTime, reason: String?) =
        resolve(FoodContentDraftStatus.REJECTED, adminAccountId, at, reason?.take(MAX_REJECT_REASON_LENGTH))

    private fun resolve(status: FoodContentDraftStatus, adminAccountId: Long, at: LocalDateTime, reason: String?) {
        check(isPending()) { "처리할 수 있는 건 검수 대기 초안뿐입니다: $reviewStatus" }
        reviewStatus = status
        resolvedBy = adminAccountId
        resolvedAt = at
        rejectReason = reason
    }

    companion object {
        const val MAX_REJECT_REASON_LENGTH = 500
    }
}
