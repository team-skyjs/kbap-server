package com.kbap.common.domain.member.dto

interface MemberCountryCount {
    val countryCode: String?
    val count: Long
}

interface MemberCountryTodayCount : MemberCountryCount {
    val todayCount: Long
}
