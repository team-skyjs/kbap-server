package com.kbap.domain.member

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MemberService internal constructor(
    private val memberJpaRepository: MemberJpaRepository,
) {
    fun findById(id: Long): Member? = findActive(id)?.toDomain()

    fun findByIdentity(provider: SocialProvider, providerUserId: String): Member? =
        memberJpaRepository
            .findByProviderAndProviderUidAndMemberStatus(provider, providerUserId, MemberStatus.ACTIVE)
            ?.toDomain()

    fun saveNew(member: Member): Member =
        try {
            memberJpaRepository.save(MemberJpaEntity.from(member)).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw MemberException(MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY)
        }

    fun update(member: Member): Member {
        val id = member.id ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        val entity = findActive(id) ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.applyDomain(member)
        return memberJpaRepository.save(entity).toDomain()
    }

    @Transactional
    fun increaseScanCount(memberId: Long) {
        val updated = memberJpaRepository.increaseScanCount(memberId)
        if (updated == 0) {
            throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        }
    }

    fun withdraw(id: Long) {
        val entity = findActive(id) ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)
        entity.withdraw()
        memberJpaRepository.save(entity)
    }

    private fun findActive(id: Long): MemberJpaEntity? =
        memberJpaRepository.findByIdAndMemberStatus(id, MemberStatus.ACTIVE)
}
