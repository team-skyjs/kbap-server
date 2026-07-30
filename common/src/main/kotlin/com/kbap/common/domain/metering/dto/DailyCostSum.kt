package com.kbap.common.domain.metering.dto

import java.math.BigDecimal
import java.time.LocalDate

data class DailyCostSum(
    val date: LocalDate,
    val costUsd: BigDecimal,
)
