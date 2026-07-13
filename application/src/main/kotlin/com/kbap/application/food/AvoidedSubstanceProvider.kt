package com.kbap.application.food

import com.kbap.domain.avoidance.AvoidanceSubstanceCode

interface AvoidedSubstanceProvider {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>
}
