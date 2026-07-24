package com.kbap.domain.scan.model

import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table

// 스캔 추출 항목 1건 = 1 row. 같은 스캔의 row 들은 image_path 를 공유한다.
// 가격(price)이 존재하는 유일한 저장소 — 음식 마스터에는 가격을 저장하지 않는다(KB-138).
@Entity
@Table(
    name = "scan_history",
    indexes = [Index(name = "idx_scan_history_recent", columnList = "member_id, created_at")],
)
class ScanHistory(
    @Column(name = "member_id", nullable = false)
    var memberId: Long = 0,

    @Column(name = "image_path", nullable = false, length = 512)
    var imagePath: String = "",

    @Column(name = "menu_name", nullable = false, length = 100)
    var menuName: String = "",

    @Column(name = "korean_name", nullable = false, length = 100)
    var koreanName: String = "",

    @Column(name = "price")
    var price: Int? = null,

    @Column(name = "food_id")
    var foodId: Long? = null,
) : BaseEntity() {
    companion object {
        fun record(
            memberId: Long,
            imagePath: String,
            menuName: String,
            koreanName: String,
            price: Int?,
            foodId: Long?,
        ): ScanHistory =
            ScanHistory(
                memberId = memberId,
                imagePath = imagePath,
                menuName = menuName,
                koreanName = koreanName,
                price = price,
                foodId = foodId,
            )
    }
}
