package com.meogo.api.application.scan.dto

data class SubmitMenuScanInput(
    val items: List<MenuScanItemInput>,
)

data class MenuScanItemInput(
    val itemId: Int,
    val rawMenuName: String,
    val boundingBox: BoundingBoxInput,
)

data class BoundingBoxInput(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)
