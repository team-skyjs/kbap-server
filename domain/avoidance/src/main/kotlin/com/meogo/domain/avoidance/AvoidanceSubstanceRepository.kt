package com.meogo.domain.avoidance

interface AvoidanceSubstanceRepository {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance>
}
