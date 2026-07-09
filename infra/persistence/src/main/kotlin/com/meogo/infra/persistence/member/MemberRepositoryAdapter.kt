package com.meogo.infra.persistence.member

import com.meogo.core.member.Member
import com.meogo.core.member.MemberErrorCode
import com.meogo.core.member.MemberException
import com.meogo.core.member.MemberRepository
import com.meogo.core.member.SocialProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

@Repository
class MemberRepositoryAdapter(
    private val memberJpaRepository: MemberJpaRepository,
) : MemberRepository {
    override fun findById(id: Long): Member? =
        memberJpaRepository.findByIdWithIdentities(id)?.toDomain()

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? =
        memberJpaRepository.findByIdentity(provider, providerUserId)?.toDomain()

    override fun saveNew(member: Member): Member =
        try {
            memberJpaRepository.save(MemberJpaEntity.from(member)).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw MemberException(MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY)
        }

    override fun update(member: Member): Member {
        val id = member.id ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        val entity = memberJpaRepository.findByIdWithIdentities(id)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.applyProfile(member)
        return memberJpaRepository.save(entity).toDomain()
    }

    override fun withdraw(id: Long) {
        val entity = memberJpaRepository.findByIdWithIdentities(id)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.identities.clear()
        entity.delete()
        memberJpaRepository.save(entity)
    }
}
