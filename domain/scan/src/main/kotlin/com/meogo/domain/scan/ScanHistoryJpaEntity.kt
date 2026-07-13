package com.meogo.domain.scan

import com.meogo.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "scan_history",
    indexes = [Index(name = "idx_scan_history_recent", columnList = "member_id, created_at")],
)
internal class ScanHistoryJpaEntity(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "food_id", nullable = false)
    var foodId: Long = 0,
) : BaseEntity() {
    companion object {
        fun from(history: ScanHistory): ScanHistoryJpaEntity =
            ScanHistoryJpaEntity(
                memberId = history.memberId,
                foodId = history.foodId,
            )
    }
}
