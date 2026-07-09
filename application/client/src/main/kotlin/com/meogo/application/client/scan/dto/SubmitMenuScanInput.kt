package com.meogo.application.client.scan.dto

data class SubmitMenuScanInput(
    val items: List<MenuScanItemInput>,
    val lang: String? = null,
)

data class MenuScanItemInput(
    val itemId: Int,
    val rawMenuName: String,
)
