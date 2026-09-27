package com.kbap.common.domain.member.dto

import java.time.LocalDateTime

interface NewMemberRow {
    val createdAt: LocalDateTime
    val countryCode: String?
    val platform: String?
}
