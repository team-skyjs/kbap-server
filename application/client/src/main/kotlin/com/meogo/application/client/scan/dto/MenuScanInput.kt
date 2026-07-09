package com.meogo.application.client.scan.dto

data class MenuScanInput(
    val items: List<MenuScanItemInput>,
)

data class MenuScanItemInput(
    val idx: Int,
    val rawMenuName: String,
)
