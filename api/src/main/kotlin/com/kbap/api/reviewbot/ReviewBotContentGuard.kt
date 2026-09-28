package com.kbap.api.reviewbot

object ReviewBotContentGuard {
    private val ALLERGY_OR_SAFETY_STEMS: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "\\bsafe\\w*", "\\ballerg\\w*", "\\bhypoallergenic\\b", "\\bgluten[- ]?free\\b", "\\bnut[- ]?free\\b",
            "\\bno (allergens?|allergy)\\b", "\\bgluten\\b", "\\bceliac\\b", "\\banaphyla\\w*", "\\bintoleran\\w*",
        ),
        "vi" to listOf("\\bdị ứng\\b", "\\ban toàn\\w*", "\\bkhông gây dị ứng\\b", "\\bgluten\\b"),
    )

    private val ALLERGY_OR_SAFETY_SUBSTRINGS: Map<String, List<String>> = mapOf(
        "ko" to listOf("알레르기", "알러지", "먹어도 안전", "안전하게 먹", "안전해요", "안전한", "글루텐"),
        "ja" to listOf("アレルギ", "安全", "グルテン"),
        "zh" to listOf("过敏", "過敏", "安全", "麸质", "麩質"),
        "th" to listOf("แพ้", "ปลอดภัย", "กลูเตน"),
    )

    private val PLACE: Map<String, List<String>> = mapOf(
        "en" to listOf("\\brestaurants?\\b", "\\bshops?\\b", "\\bstores?\\b", "\\bbranch(es)?\\b", "\\bcaf[eé]s?\\b", "\\bdiners?\\b", "\\bstalls?\\b", "\\beater(y|ies)\\b"),
        "vi" to listOf("\\bquán\\b", "\\bnhà hàng\\b", "\\bcửa hàng\\b", "\\bchi nhánh\\b"),
        "ko" to listOf("식당", "가게", "매장", "지점", "음식점", "본점"),
        "ja" to listOf("店", "レストラン", "食堂", "支店"),
        "zh" to listOf("餐厅", "餐廳", "店", "分店", "小吃摊"),
        "th" to listOf("ร้าน", "ภัตตาคาร", "สาขา"),
    )

    private val PHOTO: Map<String, List<String>> = mapOf(
        "en" to listOf("\\bphotos?\\b", "\\bpictures?\\b", "\\bpics?\\b", "\\bselfies?\\b"),
        "vi" to listOf("\\bảnh\\b", "\\bhình\\b", "\\bchụp\\b"),
        "ko" to listOf("사진", "셀카"),
        "ja" to listOf("写真"),
        "zh" to listOf("照片", "相片", "拍照"),
        "th" to listOf("รูป", "ภาพ"),
    )

    private val LINK: List<String> = listOf("http", "www.", "@", "#")

    private val LATIN_LANGUAGES: Set<String> = setOf("en", "vi")

    val LANGUAGES: Set<String> = ALLERGY_OR_SAFETY_STEMS.keys + ALLERGY_OR_SAFETY_SUBSTRINGS.keys

    private val FORBIDDEN: List<Regex> =
        ALLERGY_OR_SAFETY_STEMS.values.flatten().map { Regex(it, RegexOption.IGNORE_CASE) } +
            (ALLERGY_OR_SAFETY_SUBSTRINGS.values.flatten() + LINK).map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) } +
            (PLACE + PHOTO.mapValues { (lang, terms) -> terms + (PLACE[lang] ?: emptyList()) }).flatMap { (lang, terms) ->
                terms.map { if (lang in LATIN_LANGUAGES) Regex(it, RegexOption.IGNORE_CASE) else Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
            }

    private const val MIN_LENGTH = 20
    private const val MAX_LENGTH = 1000

    fun isAcceptable(text: String): Boolean =
        text.length in MIN_LENGTH..MAX_LENGTH && FORBIDDEN.none { it.containsMatchIn(text) }

    fun coverage(language: String): Map<String, List<String>> = mapOf(
        "allergyOrSafety" to (ALLERGY_OR_SAFETY_STEMS[language] ?: ALLERGY_OR_SAFETY_SUBSTRINGS.getValue(language)),
        "place" to PLACE.getValue(language),
        "photo" to PHOTO.getValue(language),
    )
}
