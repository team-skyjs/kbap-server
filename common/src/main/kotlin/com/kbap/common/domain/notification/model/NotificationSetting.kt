package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "notification_setting",
    uniqueConstraints = [UniqueConstraint(name = "uk_notification_setting_member", columnNames = ["member_id"])],
)
class NotificationSetting(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "helpful", nullable = false)
    var helpful: Boolean = true,

    @Column(name = "review_reminder", nullable = false)
    var reviewReminder: Boolean = true,
) : BaseEntity() {
    fun preferences() = NotificationPreferences(helpful = helpful, reviewReminder = reviewReminder)

    fun updateHelpful(enabled: Boolean) {
        helpful = enabled
    }

    fun updateReviewReminder(enabled: Boolean) {
        reviewReminder = enabled
    }

    companion object {
        fun defaultFor(memberId: Long) = NotificationSetting(memberId = memberId)
    }
}
