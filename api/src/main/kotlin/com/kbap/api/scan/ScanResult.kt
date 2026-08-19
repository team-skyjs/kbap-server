package com.kbap.api.scan

data class ScanResult(
    val items: List<ItemRiskResult>,
    val degraded: Boolean,
) {
    data class ItemRiskResult(
        val idx: Int?,
        val riskLevel: String,
        val matched: Boolean,
        val foodId: Long?,
        val name: String?,
        val koreanName: String?,
        val price: Int?,
        val avoidances: List<AvoidanceOverlap>? = emptyList(),
    )

    data class AvoidanceOverlap(
        val code: String,
        val name: String,
        val overlapped: Boolean,
        val riskLevel: String?,
    )
}
