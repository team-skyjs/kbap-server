package com.kbap.common.domain.scan

import java.time.LocalDateTime

interface RecentReadyScan {
    val foodId: Long
    val lastScannedAt: LocalDateTime
}
