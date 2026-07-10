package com.meogo.application.client.scan.dto

data class ScanInput(
    val items: List<ScanItemInput>,
)

data class ScanItemInput(
    val idx: Int,
    val rawMenuName: String,
)
