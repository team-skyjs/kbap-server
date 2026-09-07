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

    @Column(name = "activity", nullable = false)
    var activity: Boolean = true,

    @Column(name = "meal_time", nullable = false)
    var mealTime: Boolean = true,
) : BaseEntity() {
    fun preferences() = NotificationPreferences(activity = activity, mealTime = mealTime)

    fun updateActivity(enabled: Boolean) {
        activity = enabled
    }

    fun updateMealTime(enabled: Boolean) {
        mealTime = enabled
    }

    companion object {
        fun defaultFor(memberId: Long) = NotificationSetting(memberId = memberId)
    }
}
