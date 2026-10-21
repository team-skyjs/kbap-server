package com.kbap.common.domain.processedfood.model

import java.security.MessageDigest

data class ProcessedFoodSnapshot(
    val reportNo: String,
    val productName: String,
    val productGb: String?,
    val foodType: String?,
    val foodTypeState: String?,
    val manufacturer: String?,
    val seller: String?,
    val barcode: String?,
    val capacity: String?,
    val rawMaterials: String?,
    val allergens: String?,
    val nutrients: String?,
    val imageUrl: String?,
    val metaImageUrl: String?,
) {
    fun normalizedName(): String = normalizeName(productName)

    fun contentHash(): String {
        val joined = listOf(
            productName, productGb, foodType, foodTypeState, manufacturer, seller, barcode, capacity,
            rawMaterials, allergens, nutrients, imageUrl, metaImageUrl,
        ).joinToString(FIELD_SEPARATOR) { it ?: "" }
        return MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8)).toHexString()
    }

    fun isMakerUnknown(): Boolean = isUnknownMaker(manufacturer) && isUnknownMaker(seller)

    companion object {
        private const val FIELD_SEPARATOR = "\u001F"
        private val NAME_NOISE = Regex("오리지널|[\\s()\\[\\]·・,._\\-]")
        private val UNKNOWN_MAKERS = setOf("", "알수없음")

        fun normalizeName(name: String): String = name.replace(NAME_NOISE, "")

        fun isUnknownMaker(value: String?): Boolean = value.orEmpty().substringBefore('_').trim() in UNKNOWN_MAKERS
    }
}
