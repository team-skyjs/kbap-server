package com.kbap.application.support

import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.domain.member.MemberJpaRepository
import com.kbap.domain.member.MemberStatus
import org.springframework.stereotype.Component

@Component
class AvoidedSubstanceHelper(
    private val memberRepository: MemberJpaRepository,
) {
    fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> {
        if (memberId == null) return emptySet()
        val member = memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE) ?: return emptySet()
        return member.profile.avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()
    }
}
