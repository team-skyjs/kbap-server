package com.kbap.common.domain

import java.time.LocalDate

data class DailyCount(
    val date: LocalDate,
    val count: Long,
)
