package com.meogo.application.client.scan.dto

data class ScanInput(
    val items: List<ScanItemInput>,
    val memberId: Long,
)

data class ScanItemInput(
    val idx: Int,
    val rawMenuName: String,
)
