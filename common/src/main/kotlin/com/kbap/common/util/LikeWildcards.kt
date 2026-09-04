package com.kbap.common.util

object LikeWildcards {
    fun escape(keyword: String): String =
        keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
