package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot

object ProcessedFoodGrouping {
    private val SIZE_SUFFIXES = listOf("업소용", "큰사발면", "사발면", "큰사발", "사발", "미니컵", "큰컵", "왕컵", "소컵", "멀티팩", "파우치", "봉지", "번들", "점보", "cup", "컵", "팩")
    private val SIZE_PREFIXES = listOf("큰사발면", "큰사발", "사발면", "사발", "큰컵", "컵")
    private val CUP_BRANDS = listOf("컵누들", "컵반", "컵밥")
    private val QUANTITY_SUFFIX = Regex("(?i)(\\d+(\\.\\d+)?(kg|g|ml|mℓ|㎖|l|ℓ)|\\d+(개입|입|개|봉|팩)|[x*×]\\d+)$")
    private val GENERIC_NAMES = setOf("라면", "우동", "짜장", "짬뽕", "카레", "국수", "냉면", "면", "밥", "죽", "국", "탕", "김치", "만두", "떡볶이")
    private const val MIN_NAME_LENGTH = 2
    private val BRAND_PREFIXES = listOf("해태", "롯데", "남양", "일동", "진주", "성원", "사조", "오뚜기", "맛찬들")
    private val COMPANY_SUFFIXES = listOf("식품공업", "식품", "제과", "라면", "푸드", "유업", "음료", "제약", "산업", "에프앤비", "F&B", "f&b")

    fun keyOf(normalizedName: String, displayMaker: String?, ignoreSize: Boolean = true): String {
        val maker = makerKeyOf(displayMaker)
        val name = withoutBrand(normalizedName, maker)
        return (if (ignoreSize) withoutSize(name) else name) + "|" + maker
    }

    fun withoutBrand(name: String, makerKey: String): String =
        brandsOf(makerKey).firstOrNull { name.startsWith(it, ignoreCase = true) && name.length > it.length }?.let { name.substring(it.length) } ?: name

    private fun brandsOf(makerKey: String): List<String> =
        (listOf(makerKey) + COMPANY_SUFFIXES.filter { makerKey.endsWith(it) }.map { makerKey.dropLast(it.length) } + BRAND_PREFIXES.filter { makerKey.startsWith(it) })
            .filter { it.codePointCount(0, it.length) >= MIN_NAME_LENGTH }
            .sortedByDescending { it.length }

    fun foldUnknownMakers(rows: List<GroupRow>): List<String> {
        val knownByName = rows.filter { makerKeyOf(it.displayMaker).isNotEmpty() }
            .flatMap { row ->
                val maker = makerKeyOf(row.displayMaker)
                val namePart = row.groupKey.substringBeforeLast('|')
                listOf(namePart, maker + namePart, withoutSize(row.normalizedName)).map { it to row.groupKey }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, keys) -> keys.toSet() }
        return rows.map { row ->
            if (makerKeyOf(row.displayMaker).isNotEmpty()) return@map row.groupKey
            val name = row.groupKey.substringBeforeLast('|')
            listOf(name, withoutSize(withoutKnownBrand(row.normalizedName)))
                .firstNotNullOfOrNull { knownByName[it]?.singleOrNull() } ?: row.groupKey
        }
    }

    private fun withoutKnownBrand(name: String): String =
        BRAND_PREFIXES.sortedByDescending { it.length }.firstOrNull { name.startsWith(it) && name.length > it.length }?.let { name.substring(it.length) } ?: name

    data class GroupRow(val normalizedName: String, val displayMaker: String?, val groupKey: String)

    fun makerKeyOf(displayMaker: String?): String = displayMaker?.let(ProcessedFoodSnapshot::normalizeName).orEmpty()

    fun withoutSize(name: String): String {
        var current = name
        while (true) {
            val next = QUANTITY_SUFFIX.replace(current, "").let { trimmed ->
                SIZE_SUFFIXES.firstOrNull { trimmed.endsWith(it, ignoreCase = true) }?.let { trimmed.dropLast(it.length) } ?: trimmed
            }.let(::withoutSizePrefix)
            if (next == current) break
            current = next
        }
        return current.takeIf { it.codePointCount(0, it.length) >= MIN_NAME_LENGTH && it !in GENERIC_NAMES } ?: name
    }

    private fun withoutSizePrefix(name: String): String =
        if (CUP_BRANDS.any { name.startsWith(it) }) name
        else SIZE_PREFIXES.firstOrNull { name.startsWith(it) && name.length > it.length }?.let { name.substring(it.length) } ?: name
}
