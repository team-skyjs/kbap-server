package com.kbap.api.scan

import com.kbap.common.domain.CurrencyCode

data class ScanResult(
    val items: List<ItemRiskResult>,
    val degraded: Boolean,
    val currency: CurrencyCode? = null,
) {
    data class ItemRiskResult(
        val idx: Int?,
        val riskLevel: String,
        val matched: Boolean,
        val foodId: Long?,
        val name: String?,
        val koreanName: String?,
        val price: Int?,
        val similarFood: SimilarFood? = null,
    )

    data class SimilarFood(
        val foodId: Long,
        val name: String,
        val koreanName: String?,
        val description: String,
        val imageRef: String?,
    )
}
