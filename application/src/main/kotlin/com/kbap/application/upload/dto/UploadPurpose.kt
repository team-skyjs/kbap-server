package com.kbap.application.upload.dto

enum class UploadPurpose(val prefix: String) {
    MENU_SCAN("menu-scan"),
    ;

    companion object {
        fun from(value: String): UploadPurpose? = entries.firstOrNull { it.name == value }
    }
}
