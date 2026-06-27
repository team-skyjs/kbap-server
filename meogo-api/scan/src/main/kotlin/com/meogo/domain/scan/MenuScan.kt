package com.meogo.domain.scan

import java.time.Instant

/**
 * 메뉴 스캔 애그리거트 루트.
 * 불변식: 항목 1..100 개, itemId 는 스캔 내 유일.
 *
 * @param id 영속 PK. 미저장 상태면 null.
 */
class MenuScan private constructor(
    val id: Long?,
    val status: ScanStatus,
    val items: List<ScannedMenuItem>,
    val createdAt: Instant,
) {
    init {
        require(items.isNotEmpty()) { "스캔 항목은 최소 1개여야 합니다" }
        require(items.size <= MAX_ITEMS) { "스캔 항목은 최대 ${MAX_ITEMS}개입니다 (요청 ${items.size}개)" }
        val ids = items.map { it.itemId }
        require(ids.toSet().size == ids.size) { "itemId 는 스캔 내에서 중복될 수 없습니다" }
    }

    companion object {
        const val MAX_ITEMS = 100

        /** 신규 스캔 생성(동기 mock → 즉시 COMPLETED). */
        fun create(items: List<ScannedMenuItem>, createdAt: Instant = Instant.now()): MenuScan =
            MenuScan(id = null, status = ScanStatus.COMPLETED, items = items, createdAt = createdAt)

        /** 영속 → 도메인 복원(어댑터 전용). */
        fun reconstitute(
            id: Long,
            status: ScanStatus,
            items: List<ScannedMenuItem>,
            createdAt: Instant,
        ): MenuScan = MenuScan(id = id, status = status, items = items, createdAt = createdAt)
    }
}
