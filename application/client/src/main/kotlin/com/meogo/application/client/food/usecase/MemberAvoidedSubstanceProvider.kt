package com.meogo.application.client.food.usecase

import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.member.MemberRepository
import org.springframework.stereotype.Component

@Component
class MemberAvoidedSubstanceProvider(
    private val memberRepository: MemberRepository,
) : AvoidedSubstanceProvider {
    override fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> {
        if (memberId == null) return emptySet()
        val member = memberRepository.findById(memberId) ?: return emptySet()
        return member.profile.avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()
    }
}
