package com.meogo.core.member

class MemberIdentityResolver(
    private val memberRepository: MemberRepository,
) {
    fun resolve(identity: SocialIdentity): MemberResolution {
        memberRepository.findByIdentity(identity.provider, identity.providerUserId)?.let {
            return MemberResolution(member = it, isNewMember = false)
        }

        return try {
            val created = memberRepository.saveNew(Member.signUp(identity))
            MemberResolution(member = created, isNewMember = true)
        } catch (e: MemberException) {
            if (e.errorCode != MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY) throw e
            val existing = memberRepository.findByIdentity(identity.provider, identity.providerUserId)
                ?: throw e
            MemberResolution(member = existing, isNewMember = false)
        }
    }
}

data class MemberResolution(
    val member: Member,
    val isNewMember: Boolean,
)
