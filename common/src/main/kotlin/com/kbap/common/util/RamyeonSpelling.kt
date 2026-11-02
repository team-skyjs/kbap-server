package com.kbap.common.util

object RamyeonSpelling {
    private val VARIANTS = Regex("ramyun|ramen")
    private val WHOLE_WORD = setOf("ramen", "ramyun", "ramyeon")

    fun fold(key: String): String = VARIANTS.replace(key, "ramyeon")

    fun koreanTokenOf(token: String): String = if (token.lowercase() in WHOLE_WORD) "라면" else token
}
