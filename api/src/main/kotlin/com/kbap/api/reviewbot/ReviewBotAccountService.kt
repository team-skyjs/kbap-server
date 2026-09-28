package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.random.Random

@Service
class ReviewBotAccountService(
    private val memberRepository: MemberJpaRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val creation = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional
    fun ensureBots(count: Int, random: Random = Random.Default): ReviewBotAccountsResult {
        if (memberRepository.acquireNamedLock(LOCK_NAME, LOCK_TIMEOUT_SECONDS) != 1) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        try {
            return creation.execute { createMissing(count, random) }!!
        } finally {
            memberRepository.releaseNamedLock(LOCK_NAME)
        }
    }

    private fun createMissing(count: Int, random: Random): ReviewBotAccountsResult {
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

    companion object {
        const val LOCK_NAME = "kbap.review-bot.accounts"
        private const val LOCK_TIMEOUT_SECONDS = 10
    }
}

data class ReviewBotAccountsResult(
    val bots: List<Member>,
    val createdCount: Int,
)
