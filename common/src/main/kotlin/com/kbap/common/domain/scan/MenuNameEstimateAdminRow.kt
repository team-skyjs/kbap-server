package com.kbap.common.domain.scan

import java.math.BigDecimal
import java.time.LocalDateTime

interface MenuNameEstimateAdminRow {
    val id: Long
    val nameKey: String
    val kind: String
    val englishName: String?
    val description: String?
    val avoidanceCodes: String?
    val spiciness: Int
    val confidence: BigDecimal?
    val model: String
    val promptVersion: String
    val createdAt: LocalDateTime
    val promotedAt: LocalDateTime?
    val promotedBy: Long?
    val foodId: Long?
    val foodContentStatus: String?
    val foodImageRef: String?
    val scanCount: Long
}
