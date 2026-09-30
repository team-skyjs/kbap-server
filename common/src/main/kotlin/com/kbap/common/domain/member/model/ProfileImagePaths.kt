package com.kbap.common.domain.member.model

object ProfileImagePaths {
    private val DOCUMENTED_DEFAULT = Regex("^images/default/profile/[A-Za-z0-9._-]+$")
    private val PROFILE_UPLOAD = Regex("(?:^|/)images/profile/\\d{4}/\\d{2}/(\\d+)_[^/]+$")

    fun isAssignableTo(memberId: Long, path: String): Boolean =
        path in OnboardingProfileDefaults.PROFILE_IMAGE_PATHS ||
            DOCUMENTED_DEFAULT.matches(path) ||
            PROFILE_UPLOAD.find(path)?.groupValues?.get(1)?.toLongOrNull() == memberId
}
