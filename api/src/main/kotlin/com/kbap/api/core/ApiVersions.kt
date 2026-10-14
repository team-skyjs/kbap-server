package com.kbap.api.core

import org.springframework.web.accept.SemanticApiVersionParser

object ApiVersions {
    const val HOME_MARKETING_POPULAR = "1.2"
    const val SCAN_SUBSTITUTION = "3.0"

    private val parser = SemanticApiVersionParser()

    fun isAtLeast(requested: String, minimum: String): Boolean =
        parser.parseVersion(requested) >= parser.parseVersion(minimum)
}
