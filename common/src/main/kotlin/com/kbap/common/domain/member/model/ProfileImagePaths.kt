package com.kbap.common.domain.member.model

object ProfileImagePaths {
    private val DOCUMENTED_DEFAULT = Regex("^images/default/profile/[A-Za-z0-9._-]+$")

    fun isDefault(path: String): Boolean =
        path in OnboardingProfileDefaults.PROFILE_IMAGE_PATHS || DOCUMENTED_DEFAULT.matches(path)

    fun isAssignableTo(memberId: Long, path: String, keyPrefix: String): Boolean =
        isDefault(path) || issuedKeyOf(memberId, keyPrefix).matches(path)

    private fun issuedKeyOf(memberId: Long, keyPrefix: String): Regex {
        val prefix = keyPrefix.trim('/').takeIf { it.isNotEmpty() }?.let { Regex.escape("$it/") }.orEmpty()
        return Regex("^${prefix}images/profile/\\d{4}/\\d{2}/${memberId}_[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.[a-z0-9]+$")
    }
}
