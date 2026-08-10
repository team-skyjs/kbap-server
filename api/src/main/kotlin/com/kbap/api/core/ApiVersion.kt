package com.kbap.api.core

// 계약 버전 표기는 yyyy.mm.sprint차수 (예: 2026.08.07) — 토스 캘린더 버저닝 커스텀(2026-08-10 결정).
data class ApiVersion(
    val year: Int,
    val month: Int,
    val sprint: Int,
) : Comparable<ApiVersion> {
    override fun compareTo(other: ApiVersion): Int =
        compareValuesBy(this, other, ApiVersion::year, ApiVersion::month, ApiVersion::sprint)

    companion object {
        // 파싱 불가(오타·비정상 헤더·미전송)는 null — 호출부가 종전 계약으로 폴백하도록 예외를 쓰지 않는다.
        fun parseOrNull(value: String?): ApiVersion? {
            val parts = value?.trim()?.split('.') ?: return null
            if (parts.size != 3) return null
            val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return ApiVersion(year = numbers[0], month = numbers[1], sprint = numbers[2])
        }
    }
}
