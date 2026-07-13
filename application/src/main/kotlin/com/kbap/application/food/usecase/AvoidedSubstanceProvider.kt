package com.kbap.application.food.usecase

import com.kbap.domain.avoidance.AvoidanceSubstanceCode

interface AvoidedSubstanceProvider {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>
}
