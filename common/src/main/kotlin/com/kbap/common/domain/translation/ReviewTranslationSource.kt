package com.kbap.common.domain.translation

import java.security.MessageDigest

object ReviewTranslationSource {
    const val CACHE_VERSION = "4"

    fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$CACHE_VERSION\n$source".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
