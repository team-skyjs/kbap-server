package com.kbap.common.domain.processedfood.dto

import com.kbap.common.domain.processedfood.model.CutoutReviewStatus

interface CutoutReviewCount {
    val route: String?
    val reviewStatus: CutoutReviewStatus
    val total: Long
    val reviewedInRange: Long
}
