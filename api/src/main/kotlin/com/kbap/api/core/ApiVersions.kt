package com.kbap.api.core

import org.springframework.web.accept.SemanticApiVersionParser

object ApiVersions {
    private val parser = SemanticApiVersionParser()

    fun isAtLeast(requested: String, minimum: String): Boolean =
        parser.parseVersion(requested) >= parser.parseVersion(minimum)
}
