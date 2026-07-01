package com.meogo.core.avoidance

interface AvoidanceSubstanceRepository {
    fun byCategory(category: AvoidanceCategory): List<AvoidanceSubstance>

    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
