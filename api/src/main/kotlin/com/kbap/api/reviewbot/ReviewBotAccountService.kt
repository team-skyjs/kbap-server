package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.OnboardingProfileDefaults
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)
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
            if (memberRepository.releaseNamedLock(LOCK_NAME) != 1) {
                log.error("리뷰 봇 계정 생성 잠금을 풀지 못했습니다 — 잠금을 얻은 커넥션과 다른 커넥션에서 해제를 시도했을 수 있습니다: {}", LOCK_NAME)
            }
        }
    }

    private fun createMissing(count: Int, random: Random): ReviewBotAccountsResult {
        val existing = getBots()
        val usedNicknames = existing.mapNotNull { it.nickname }.toMutableSet()
        val created = (existing.size until count).map {
            val countryCode = ReviewBotCountries.pick(random)
            val nickname = ReviewBotNicknames.pick(countryCode, usedNicknames, random) ?: run {
                log.warn("리뷰 봇 닉네임 풀이 소진돼 봇을 더 만들 수 없습니다 — 이름 풀을 늘려야 합니다")
                throw BusinessException(ErrorCode.INVALID_REQUEST)
            }
            usedNicknames += nickname
            memberRepository.save(
                Member.reviewBot(
                    countryCode = countryCode,
                    nickname = nickname,
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
