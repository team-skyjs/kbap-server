package com.meogo.application.scan

/**
 * 스캔 제출 입력(application 레벨 타입). api 는 도메인 타입을 모르므로 이 평면 타입으로 받는다.
 */
data class SubmitMenuScanCommand(
    val items: List<Item>,
) {
    data class Item(
        val itemId: Int,
        val rawMenuName: String,
        val boundingBox: Box,
    )

    data class Box(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )
}
