package com.meogo.application.food.usecase

import com.meogo.domain.avoidance.AvoidanceSubstanceCode

interface AvoidedSubstanceProvider {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>
}
