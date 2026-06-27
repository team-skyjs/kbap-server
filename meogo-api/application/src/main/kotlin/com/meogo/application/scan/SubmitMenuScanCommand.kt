package com.meogo.application.scan

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
