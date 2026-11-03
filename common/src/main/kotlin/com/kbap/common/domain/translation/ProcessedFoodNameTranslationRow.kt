package com.kbap.common.domain.translation

interface ProcessedFoodNameTranslationRow {
    val targetId: Long
    val targetType: String
    val language: String
    val sourceHash: String
    val translatedText: String
}
