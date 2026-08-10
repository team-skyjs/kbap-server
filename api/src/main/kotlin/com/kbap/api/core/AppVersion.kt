package com.kbap.api.core

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    companion object {
        // 파싱 불가(오타·비정상 헤더)는 null — 호출부가 구버전 동작으로 폴백하도록 예외를 쓰지 않는다.
        fun parseOrNull(value: String?): AppVersion? {
            val parts = value?.trim()?.split('.') ?: return null
            if (parts.isEmpty() || parts.size > 3) return null
            val numbers = parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}
