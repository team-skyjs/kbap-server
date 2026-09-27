package com.kbap.api.reviewbot

import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.random.Random

@Service
class ReviewBotAccountService(
    private val memberRepository: MemberJpaRepository,
) {
    @Transactional
    fun ensureBots(count: Int, random: Random = Random.Default): ReviewBotAccountsResult {
        val existing = getBots()
        val created = (existing.size until count).map {
            memberRepository.save(
                Member.reviewBot(
                    countryCode = ReviewBotCountries.pick(random),
                    nickname = OnboardingProfileDefaults.randomNickname(),
                    profileImagePath = OnboardingProfileDefaults.randomProfileImagePath(),
                ),
            )
        }
        return ReviewBotAccountsResult(bots = existing + created, createdCount = created.size)
    }

    @Transactional(readOnly = true)
    fun getBots(): List<Member> = memberRepository.findByIsBotTrueAndMemberStatus(MemberStatus.ACTIVE)
}

data class ReviewBotAccountsResult(
    val bots: List<Member>,
    val createdCount: Int,
)
