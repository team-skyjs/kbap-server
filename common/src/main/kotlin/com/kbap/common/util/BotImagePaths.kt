package com.kbap.common.util

import java.util.Locale

object BotImagePaths {
    private val escapedByte = Regex("%([0-9a-fA-F]{2})")

    fun isProtected(path: String): Boolean {
        var decoded = path
        while (true) {
            if (hasNamespace(decoded)) return true
            val next = escapedByte.replace(decoded) { it.groupValues[1].toInt(16).toChar().toString() }
            if (next == decoded) return false
            decoded = next
        }
    }

    private fun hasNamespace(path: String): Boolean {
        val parts = path.replace('\\', '/').lowercase(Locale.ROOT).split('/')
        if (parts.zipWithNext().any { (a, b) -> a == "images" && b == "review-bot" }) return true
        val normalized = mutableListOf<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (normalized.isNotEmpty()) normalized.removeAt(normalized.lastIndex)
                else -> normalized.add(part)
            }
        }
        return normalized.zipWithNext().any { (a, b) -> a == "images" && b == "review-bot" }
    }
}
