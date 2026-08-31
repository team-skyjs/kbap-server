package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

@Entity
@Table(
    name = "scan_history",
    indexes = [Index(name = "idx_scan_history_recent", columnList = "member_id, created_at")],
)
class ScanHistory(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "price")
    var price: Int? = null,

    @Column(name = "food_id")
    var foodId: Long? = null,
) : BaseEntity() {
    companion object {
        fun record(
            memberId: Long,
            price: Int?,
            foodId: Long?,
        ): ScanHistory =
            ScanHistory(
                memberId = memberId,
                price = price,
                foodId = foodId,
            )
    }
}
