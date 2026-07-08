package com.meogo.core.scan

enum class PendingMenuStatus {
    PENDING,
    RESOLVED,
    REJECTED,
}

class PendingMenu private constructor(
    val id: Long?,
    val name: String,
    val status: PendingMenuStatus,
) {
    companion object {
        fun of(name: String): PendingMenu {
            require(name.isNotBlank()) { "PendingMenu.name 은 blank 일 수 없습니다" }
            return PendingMenu(id = null, name = name, status = PendingMenuStatus.PENDING)
        }

        fun reconstitute(id: Long, name: String, status: PendingMenuStatus): PendingMenu =
            PendingMenu(id = id, name = name, status = status)
    }
}
