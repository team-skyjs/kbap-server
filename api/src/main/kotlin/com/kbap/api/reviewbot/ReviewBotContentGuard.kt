package com.kbap.api.reviewbot

object ReviewBotContentGuard {
    private val FORBIDDEN: List<Regex> = listOf(
        "allerg", "allergen", "safe to eat", "safe for", "gluten", "celiac", "anaphyla",
        "알레르기", "알러지", "먹어도 안전", "안전하게 먹",
        "アレルギ", "食べても安全", "過敏", "过敏", "安全食用", "แพ้",
        "restaurant", "shop", "store", "branch", "cafe", "diner", "stall",
        "식당", "가게", "매장", "지점", "店", "餐厅", "餐廳", "ร้าน", "quán", "nhà hàng",
        "photo", "picture", "pic ", "사진", "写真", "照片", "รูป", "ảnh",
        "http", "www.", "@", "#",
    ).map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    private const val MIN_LENGTH = 20
    private const val MAX_LENGTH = 1000

    fun isAcceptable(text: String): Boolean =
        text.length in MIN_LENGTH..MAX_LENGTH && FORBIDDEN.none { it.containsMatchIn(text) }
}
