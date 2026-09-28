package com.kbap.api.reviewbot

object ReviewBotContentGuard {
    private val ALLERGY_OR_SAFETY: Map<String, List<String>> = mapOf(
        "en" to listOf("allerg", "safe to eat", "safe for", "gluten", "celiac", "anaphyla", "intoleran"),
        "ko" to listOf("알레르기", "알러지", "먹어도 안전", "안전하게 먹", "안전해요", "글루텐"),
        "ja" to listOf("アレルギ", "食べても安全", "安全に食べ", "グルテン"),
        "zh" to listOf("过敏", "過敏", "安全食用", "可以安全", "麸质", "麩質"),
        "th" to listOf("แพ้", "ปลอดภัย", "กลูเตน"),
        "vi" to listOf("dị ứng", "an toàn", "không gây dị ứng", "gluten"),
    )

    private val PLACE: Map<String, List<String>> = mapOf(
        "en" to listOf("restaurant", "shop", "store", "branch", "cafe", "diner", "stall", "eatery"),
        "ko" to listOf("식당", "가게", "매장", "지점", "음식점", "본점"),
        "ja" to listOf("店", "レストラン", "食堂", "支店"),
        "zh" to listOf("餐厅", "餐廳", "店", "分店", "小吃摊"),
        "th" to listOf("ร้าน", "ภัตตาคาร", "สาขา"),
        "vi" to listOf("quán", "nhà hàng", "cửa hàng", "chi nhánh"),
    )

    private val PHOTO: Map<String, List<String>> = mapOf(
        "en" to listOf("photo", "picture", "pic ", "selfie"),
        "ko" to listOf("사진", "셀카"),
        "ja" to listOf("写真"),
        "zh" to listOf("照片", "相片", "拍照"),
        "th" to listOf("รูป", "ภาพ"),
        "vi" to listOf("ảnh", "hình", "chụp"),
    )

    private val LINK: List<String> = listOf("http", "www.", "@", "#")

    val LANGUAGES: Set<String> = ALLERGY_OR_SAFETY.keys

    private val FORBIDDEN: List<Regex> =
        (ALLERGY_OR_SAFETY.values.flatten() + PLACE.values.flatten() + PHOTO.values.flatten() + LINK)
            .map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    private const val MIN_LENGTH = 20
    private const val MAX_LENGTH = 1000

    fun isAcceptable(text: String): Boolean =
        text.length in MIN_LENGTH..MAX_LENGTH && FORBIDDEN.none { it.containsMatchIn(text) }

    fun coverage(language: String): Map<String, List<String>> = mapOf(
        "allergyOrSafety" to ALLERGY_OR_SAFETY.getValue(language),
        "place" to PLACE.getValue(language),
        "photo" to PHOTO.getValue(language),
    )
}
