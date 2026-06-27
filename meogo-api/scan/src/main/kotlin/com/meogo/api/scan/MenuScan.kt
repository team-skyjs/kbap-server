package com.meogo.api.scan

class MenuScan private constructor(
    val id: Long?,
    val status: ScanStatus,
    val items: List<ScannedMenuItem>,
) {
    init {
        require(items.isNotEmpty()) { "스캔 항목은 최소 1개여야 합니다" }
        require(items.size <= MAX_ITEMS) { "스캔 항목은 최대 ${MAX_ITEMS}개입니다 (요청 ${items.size}개)" }
        val ids = items.map { it.itemId }
        require(ids.toSet().size == ids.size) { "itemId 는 스캔 내에서 중복될 수 없습니다" }
    }

    companion object {
        const val MAX_ITEMS = 100

        fun create(spec: CreationSpec): MenuScan =
            MenuScan(id = null, status = ScanStatus.COMPLETED, items = spec.items)

        fun reconstitute(
            id: Long,
            status: ScanStatus,
            items: List<ScannedMenuItem>,
        ): MenuScan = MenuScan(id = id, status = status, items = items)
    }

    data class CreationSpec(
        val items: List<ScannedMenuItem>,
    )
}
