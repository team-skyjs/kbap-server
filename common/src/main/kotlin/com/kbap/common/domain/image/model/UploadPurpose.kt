package com.kbap.common.domain.image.model

enum class UploadPurpose(val prefix: String) {
    MENU_SCAN("scans"),
    REVIEW("review"),
    PROFILE_IMAGE("profile"),
    COMMUNITY("community"),
    FOOD("food"),
    ;

    companion object {
        fun from(value: String): UploadPurpose? = entries.firstOrNull { it.name == value }
    }
}
