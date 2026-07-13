package com.meogo.application.client.member

import com.meogo.domain.member.Member
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.Ranking
import com.meogo.domain.member.SocialProvider

class FakeMemberRepository : MemberRepository {
    private val store = mutableMapOf<Long, Member>()

    fun seed(member: Member) {
        store[member.id!!] = member
    }

    override fun findById(id: Long): Member? = store[id]

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? =
        store.values.firstOrNull { it.identity.provider == provider && it.identity.providerUserId == providerUserId }

    override fun saveNew(member: Member): Member {
        val id = (store.keys.maxOrNull() ?: 0L) + 1
        val saved = Member.reconstitute(
            id = id,
            identity = member.identity,
            profile = member.profile,
            onboardingCompleted = member.onboardingCompleted,
            ranking = member.ranking,
        )
        store[id] = saved
        return saved
    }

    override fun increaseScanCount(memberId: Long) {
        val member = store[memberId] ?: return
        store[memberId] = Member.reconstitute(
            id = memberId,
            identity = member.identity,
            profile = member.profile,
            onboardingCompleted = member.onboardingCompleted,
            ranking = Ranking.of(
                scanCount = member.ranking.scanCount + 1,
                reviewCount = member.ranking.reviewCount,
                uniqueReviewedFoodCount = member.ranking.uniqueReviewedFoodCount,
            ),
        )
    }

    override fun update(member: Member): Member {
        store[member.id!!] = member
        return member
    }

    override fun withdraw(id: Long) {
        store.remove(id)
    }
}
