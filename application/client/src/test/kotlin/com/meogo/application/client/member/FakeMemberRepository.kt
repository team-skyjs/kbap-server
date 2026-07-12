package com.meogo.application.client.member

import com.meogo.core.member.Member
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.SocialProvider

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
            scanCount = member.scanCount,
            reviewCount = member.reviewCount,
            uniqueReviewedFoodCount = member.uniqueReviewedFoodCount,
        )
        store[id] = saved
        return saved
    }

    override fun update(member: Member): Member {
        store[member.id!!] = member
        return member
    }

    override fun withdraw(id: Long) {
        store.remove(id)
    }
}
