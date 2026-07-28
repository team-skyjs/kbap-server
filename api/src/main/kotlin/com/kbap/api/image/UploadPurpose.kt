package com.kbap.api.image

enum class UploadPurpose(val prefix: String) {
    MENU_SCAN("scan"),
    REVIEW("review"),
    PROFILE_IMAGE("profile"),
    ;

    companion object {
        fun from(value: String): UploadPurpose? = entries.firstOrNull { it.name == value }
    }
}
