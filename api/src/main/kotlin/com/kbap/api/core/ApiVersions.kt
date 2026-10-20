package com.kbap.api.core

import org.springframework.web.accept.SemanticApiVersionParser

object ApiVersions {
    const val HOME_MARKETING_POPULAR = "1.2"
    const val HOME_TOP_RATED = "1.3"
    const val SCAN_SUBSTITUTION = "3.0"
    const val FOOD_NAME_SORT = "3.1"
    const val PROCESSED_FOOD = "3.2"
    const val SCAN_BATCH = "3.3"

    private val parser = SemanticApiVersionParser()

    fun isAtLeast(requested: String, minimum: String): Boolean =
        parser.parseVersion(requested) >= parser.parseVersion(minimum)
}
