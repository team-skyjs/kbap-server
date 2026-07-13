package com.meogo.infra.persistence.member

import com.meogo.domain.member.Member
import com.meogo.domain.member.MemberErrorCode
import com.meogo.domain.member.MemberException
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.SocialProvider
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class MemberRepositoryAdapter(
    private val memberJpaRepository: MemberJpaRepository,
) : MemberRepository {
    override fun findById(id: Long): Member? = findActive(id)?.toDomain()

    override fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? =
        memberJpaRepository
            .findByProviderAndProviderUidAndMemberStatus(provider, providerUserId, MemberStatus.ACTIVE)
            ?.toDomain()

    override fun saveNew(member: Member): Member =
        try {
            memberJpaRepository.save(MemberJpaEntity.from(member)).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw MemberException(MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY)
        }

    override fun update(member: Member): Member {
        val id = member.id ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        val entity = findActive(id) ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.applyDomain(member)
        return memberJpaRepository.save(entity).toDomain()
    }

    @Transactional
    override fun increaseScanCount(memberId: Long) {
        val updated = memberJpaRepository.increaseScanCount(memberId)
        if (updated == 0) {
            throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        }
    }

    override fun withdraw(id: Long) {
        val entity = findActive(id) ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.withdraw()
        memberJpaRepository.save(entity)
    }

    private fun findActive(id: Long): MemberJpaEntity? =
        memberJpaRepository.findByIdAndMemberStatus(id, MemberStatus.ACTIVE)
}
