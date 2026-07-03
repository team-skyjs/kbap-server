package com.meogo.core.avoidance

interface AvoidanceSubstanceRepository {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
