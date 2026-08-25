package com.kbap.common.domain.member.dto

import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import java.time.LocalDateTime

data class AdminMemberRow(
    val id: Long,
    val nickname: String?,
    val email: String?,
    val provider: SocialProvider,
    val memberStatus: MemberStatus,
    val onboardingCompleted: Boolean,
    val withdrawn: Boolean,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
