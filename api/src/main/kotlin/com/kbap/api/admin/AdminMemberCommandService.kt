package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.port.auth.SocialAccountDeleter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class AdminMemberCommandService(
    private val memberRepository: MemberJpaRepository,
    private val socialAccountDeleter: SocialAccountDeleter,
    private val auditRecorder: AdminAuditRecorder,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val separateTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @Transactional
    fun changeStatus(adminId: Long, memberId: Long, status: MemberStatus, reason: String?): AdminMemberActionResponse {
        val member = getMember(memberId)
        val before = member.memberStatus
        when (status) {
            MemberStatus.SUSPENDED -> {
                if (reason.isNullOrBlank()) throw BusinessException(ErrorCode.INVALID_REQUEST)
                member.suspend(reason.trim())
            }
            MemberStatus.ACTIVE -> member.reinstate()
        }
        if (before != member.memberStatus) {
            auditRecorder.record(
                adminId, AdminAuditAction.MEMBER_STATUS, AdminAuditTargetType.MEMBER, member.id,
                mapOf("memberStatus" to before.name), mapOf("memberStatus" to member.memberStatus.name), note = reason,
            )
        }
        return toResponse(member)
    }

    @Transactional
    fun resetProfile(adminId: Long, memberId: Long, resetNickname: Boolean, resetProfileImage: Boolean): AdminMemberActionResponse {
        if (!resetNickname && !resetProfileImage) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val member = getMember(memberId)
        val before = mapOf("nickname" to member.nickname, "profileImageUrl" to member.profileImageUrl)
        if (resetNickname) member.resetNickname()
        if (resetProfileImage) member.resetProfileImage()
        auditRecorder.record(
            adminId, AdminAuditAction.MEMBER_PROFILE_RESET, AdminAuditTargetType.MEMBER, member.id,
            before, mapOf("nickname" to member.nickname, "profileImageUrl" to member.profileImageUrl),
        )
        return toResponse(member)
    }

    @Transactional
    fun unlockScan(adminId: Long, memberId: Long): AdminMemberActionResponse {
        val member = getMember(memberId)
        val before = member.scanUnlocked
        member.unlockScan()
        auditRecorder.record(
            adminId, AdminAuditAction.MEMBER_SCAN_UNLOCK, AdminAuditTargetType.MEMBER, member.id,
            mapOf("scanUnlocked" to before), mapOf("scanUnlocked" to true),
        )
        return toResponse(member)
    }

    fun withdraw(adminId: Long, memberId: Long): AdminMemberActionResponse {
        val member = memberRepository.findByIdIncludingWithdrawn(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        if (member.isDeleted()) return toResponse(member)
        try {
            socialAccountDeleter.delete(member.provider, member.providerUid)
        } catch (e: Exception) {
            log.error("관리자 강제 탈퇴 — 소셜 계정 삭제 실패 memberId={}", memberId, e)
            separateTransaction.executeWithoutResult {
                auditRecorder.record(
                    adminId, AdminAuditAction.MEMBER_WITHDRAW_FAILED, AdminAuditTargetType.MEMBER, memberId,
                    null, null, note = e.message ?: e.javaClass.simpleName,
                )
            }
            throw BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
        return separateTransaction.execute {
            val managed = memberRepository.findById(memberId).orElse(null)
            if (managed != null) {
                managed.withdraw()
                auditRecorder.record(adminId, AdminAuditAction.MEMBER_WITHDRAW, AdminAuditTargetType.MEMBER, memberId, null, null)
            }
            toResponse(managed ?: member)
        }!!
    }

    private fun getMember(memberId: Long): Member =
        memberRepository.findById(memberId).orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

    private fun toResponse(member: Member) = AdminMemberActionResponse(
        id = member.id,
        memberStatus = member.memberStatus,
        nickname = member.nickname,
        profileImageUrl = member.profileImageUrl,
        scanUnlocked = member.scanUnlocked,
        withdrawn = member.isDeleted(),
    )
}
