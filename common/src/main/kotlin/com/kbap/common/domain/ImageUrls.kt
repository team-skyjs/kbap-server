package com.kbap.common.domain

object ImageUrls {
    fun resolve(base: String, ref: String?): String? {
        if (ref == null) return null
        if (isAbsoluteUrl(ref)) return ref
        val trimmedBase = base.trim()
        if (trimmedBase.isEmpty()) return ref
        return trimmedBase.trimEnd('/') + "/" + ref.trimStart('/')
    }

    fun isAbsoluteUrl(ref: String): Boolean =
        ref.startsWith("http://", ignoreCase = true) || ref.startsWith("https://", ignoreCase = true)
}
