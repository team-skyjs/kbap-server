package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import java.security.MessageDigest

object ProcessedFoodNameSource {
    private val GENERIC_NAMES = setOf("라면", "비빔면", "짜장면", "냉면", "우유", "식혜", "콜라", "사이다", "주스", "과자", "만두", "김치")

    fun of(name: String, maker: String?, translator: ProcessedFoodTextTranslator?): String {
        val makerKey = maker?.let { ProcessedFoodSnapshot.normalizeName(it) }?.takeIf { it.isNotEmpty() } ?: return name
        var index = 0
        var matched = 0
        while (index < name.length && matched < makerKey.length) {
            if (name[index].isWhitespace()) {
                index++
                continue
            }
            if (!name[index].equals(makerKey[matched], ignoreCase = true)) return name
            index++
            matched++
        }
        val rest = name.substring(index).trim()
        if (matched < makerKey.length || rest.isEmpty() || ProcessedFoodSnapshot.normalizeName(rest) in GENERIC_NAMES) return name
        val official = translator?.officialLatinOf(name)
        return if (official != null && official != translator.officialLatinOf(rest)) name else rest
    }

    fun hashOf(source: String): String =
        MessageDigest.getInstance("SHA-256").digest("${ProcessedFoodTranslationService.CACHE_VERSION}\n$source".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
