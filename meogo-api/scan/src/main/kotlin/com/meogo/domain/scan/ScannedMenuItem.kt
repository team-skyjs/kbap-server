package com.meogo.domain.scan

data class ScannedMenuItem(
    val itemId: Int,
    val rawMenuName: String,
    val boundingBox: BoundingBox,
    val receivedOrder: Int,
    val assessment: MenuItemAssessment,
) {
    init {
        require(rawMenuName.isNotBlank()) { "rawMenuName 은 blank 일 수 없습니다" }
    }
}
