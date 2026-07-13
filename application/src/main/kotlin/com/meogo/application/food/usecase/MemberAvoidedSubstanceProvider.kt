package com.meogo.application.food.usecase

import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.member.MemberService
import org.springframework.stereotype.Component

@Component
class MemberAvoidedSubstanceProvider(
    private val memberService: MemberService,
) : AvoidedSubstanceProvider {
    override fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> {
        if (memberId == null) return emptySet()
        val member = memberService.findById(memberId) ?: return emptySet()
        return member.profile.avoidanceSubstanceCodes
            .mapNotNull { ref -> AvoidanceSubstanceCode.entries.firstOrNull { it.name == ref.value } }
            .toSet()
    }
}
