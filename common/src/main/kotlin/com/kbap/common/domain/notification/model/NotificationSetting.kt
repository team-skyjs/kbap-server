package com.kbap.common.domain.notification.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "notification_setting",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_notification_setting_member_installation", columnNames = ["member_id", "installation_id"]),
    ],
)
class NotificationSetting(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "installation_id", nullable = false, length = 36)
    var installationId: String = "",

    @Column(name = "activity", nullable = false)
    var activity: Boolean = false,

    @Column(name = "meal_time", nullable = false)
    var mealTime: Boolean = false,

    @Column(name = "news", nullable = false)
    var news: Boolean = false,
) : BaseEntity() {
    fun updateActivity(enabled: Boolean) {
        activity = enabled
    }

    fun updateMealTime(enabled: Boolean) {
        mealTime = enabled
    }

    fun updateNews(enabled: Boolean) {
        news = enabled
    }

    companion object {
        fun defaultFor(memberId: Long, installationId: String) = NotificationSetting(memberId = memberId, installationId = installationId)
    }
}
