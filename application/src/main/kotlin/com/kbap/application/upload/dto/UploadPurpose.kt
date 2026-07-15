package com.kbap.application.upload.dto

enum class UploadPurpose(val prefix: String) {
    MENU_SCAN("scan"),
    REVIEW("review"),
    ;

    companion object {
        fun from(value: String): UploadPurpose? = entries.firstOrNull { it.name == value }
    }
}
