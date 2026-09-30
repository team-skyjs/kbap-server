package com.kbap.common.domain.scan

import com.kbap.common.domain.food.model.Food
import java.time.LocalDateTime

interface RecentScannedFood {
    val food: Food
    val scannedAt: LocalDateTime
}
