package com.kbap.common.domain.processedfood.dto

import java.time.LocalDateTime

interface ProcessedFoodCandidateRow {
    val id: Long
    val normalizedName: String
    val manufacturer: String?
    val seller: String?
    val makerSupplement: String?
    val lastSeenAt: LocalDateTime
    val source: String
    val foodType: String?
}
