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

    val supported = listOf("1.0", "1.1", HOME_MARKETING_POPULAR, HOME_TOP_RATED, "2.0",
        SCAN_SUBSTITUTION, FOOD_NAME_SORT, PROCESSED_FOOD, SCAN_BATCH)
    private val labels = supported.associateBy(parser::parseVersion)

    fun metricLabel(header: String?): String {
        if (header.isNullOrBlank()) return "missing"
        val version = runCatching { parser.parseVersion(header) }.getOrNull()
        return labels[version] ?: "unsupported"
    }

    fun isAtLeast(requested: String, minimum: String): Boolean =
        parser.parseVersion(requested) >= parser.parseVersion(minimum)
}
