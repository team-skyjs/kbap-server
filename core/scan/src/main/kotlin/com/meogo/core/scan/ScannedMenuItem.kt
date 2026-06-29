package com.meogo.core.scan

data class ScannedMenuItem(
    val id: Long? = null,
    val itemId: Int,
    val rawMenuName: String,
    val boundingBox: BoundingBox,
    val assessment: MenuItemAssessment,
) {
    init {
        require(rawMenuName.isNotBlank()) { "rawMenuName 은 blank 일 수 없습니다" }
    }
}
