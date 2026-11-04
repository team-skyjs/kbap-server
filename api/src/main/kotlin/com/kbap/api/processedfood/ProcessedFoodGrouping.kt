package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot

object ProcessedFoodGrouping {
    private val SIZE_SUFFIXES = listOf("업소용", "큰사발면", "사발면", "큰사발", "미니컵", "큰컵", "왕컵", "소컵", "멀티팩", "파우치", "봉지", "번들", "점보", "cup", "컵", "팩")
    private val QUANTITY_SUFFIX = Regex("(?i)(\\d+(\\.\\d+)?(kg|g|ml|mℓ|㎖|l|ℓ)|\\d+(개입|입|개|봉|팩)|[x*×]\\d+)$")
    private val GENERIC_NAMES = setOf("라면", "우동", "짜장", "짬뽕", "카레", "국수", "냉면", "면", "밥", "죽", "국", "탕", "김치", "만두", "떡볶이")
    private const val MIN_NAME_LENGTH = 2
    private val COMPANY_SUFFIXES = listOf("식품공업", "식품", "제과", "라면", "푸드", "유업", "음료", "제약", "산업", "에프앤비", "F&B", "f&b")

    fun keyOf(normalizedName: String, displayMaker: String?, ignoreSize: Boolean = true): String {
        val maker = makerKeyOf(displayMaker)
        val name = withoutBrand(normalizedName, maker)
        return (if (ignoreSize) withoutSize(name) else name) + "|" + maker
    }

    fun withoutBrand(name: String, makerKey: String): String =
        brandsOf(makerKey).firstOrNull { name.startsWith(it, ignoreCase = true) && name.length > it.length }?.let { name.substring(it.length) } ?: name

    private fun brandsOf(makerKey: String): List<String> =
        (listOf(makerKey) + COMPANY_SUFFIXES.filter { makerKey.endsWith(it) }.map { makerKey.dropLast(it.length) })
            .filter { it.codePointCount(0, it.length) >= MIN_NAME_LENGTH }
            .sortedByDescending { it.length }

    fun makerKeyOf(displayMaker: String?): String = displayMaker?.let(ProcessedFoodSnapshot::normalizeName).orEmpty()

    fun withoutSize(name: String): String {
        var current = name
        while (true) {
            val next = QUANTITY_SUFFIX.replace(current, "").let { trimmed ->
                SIZE_SUFFIXES.firstOrNull { trimmed.endsWith(it, ignoreCase = true) }?.let { trimmed.dropLast(it.length) } ?: trimmed
            }
            if (next == current) break
            current = next
        }
        return current.takeIf { it.codePointCount(0, it.length) >= MIN_NAME_LENGTH && it !in GENERIC_NAMES } ?: name
    }
}
