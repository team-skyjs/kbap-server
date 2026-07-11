package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstanceCode

interface AvoidedSubstanceProvider {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>
}
