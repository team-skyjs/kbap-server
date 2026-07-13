package com.meogo.domain.member

interface MemberRepository {
    fun findById(id: Long): Member?

    fun findByIdentity(provider: SocialProvider, providerUserId: String): Member?

    fun saveNew(member: Member): Member

    fun update(member: Member): Member

    fun increaseScanCount(memberId: Long)

    fun withdraw(id: Long)
}
