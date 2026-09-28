package com.kbap.common.domain.review.model

enum class ReviewBotForbiddenCategory(
    val promptRule: String,
    private val stems: Map<String, List<String>>,
    private val substrings: Map<String, List<String>>,
) {
    ALLERGY_SAFETY(
        promptRule = "Never mention allergies, allergens, dietary safety, or whether it is safe to eat for anyone.",
        stems = mapOf(
            "en" to listOf("\\bsafe\\w*", "\\ballerg\\w*", "\\bhypoallergenic\\b", "\\b\\w+[- ]?free\\b", "\\bno (allergens?|allergy)\\b", "\\bceliac\\b", "\\banaphyla\\w*", "\\bintoleran\\w*"),
            "vi" to listOf("\\bdị ứng\\b", "\\ban toàn\\w*", "\\bkhông (có|chứa|gây) \\w+"),
        ),
        substrings = mapOf(
            "ko" to listOf("알레르기", "알러지", "먹어도 안전", "안전하게 먹", "안전해요", "안전한"),
            "ja" to listOf("アレルギ", "安全"),
            "zh" to listOf("过敏", "過敏", "安全"),
            "th" to listOf("แพ้", "ปลอดภัย"),
        ),
    ),
    DIET_SUITABILITY(
        promptRule = "Never say the dish is vegan, vegetarian, halal, kosher, gluten-free, dairy-free, low-carb, keto, or suitable for any diet or condition.",
        stems = mapOf(
            "en" to listOf("\\bvegan\\b", "\\bvegetarian\\b", "\\bhalal\\b", "\\bkosher\\b", "\\bketo\\b", "\\blow[- ]?(carb|sodium|fat)\\b", "\\bdiabet\\w*", "\\bgluten\\b"),
            "vi" to listOf("\\bthuần chay\\b", "\\băn chay\\b", "\\bhalal\\b", "\\bgluten\\b", "\\btiểu đường\\b"),
        ),
        substrings = mapOf(
            "ko" to listOf("비건", "채식", "할랄", "코셔", "글루텐", "무설탕", "저탄고지", "당뇨"),
            "ja" to listOf("ビーガン", "ヴィーガン", "ベジタリアン", "ハラル", "グルテン", "糖質制限", "糖尿"),
            "zh" to listOf("纯素", "純素", "素食", "清真", "无麸质", "無麩質", "低碳", "糖尿"),
            "th" to listOf("วีแกน", "มังสวิรัติ", "ฮาลาล", "ปราศจากกลูเตน", "กลูเตน", "เบาหวาน"),
        ),
    ),
    HEALTH_CLAIM(
        promptRule = "Never make health, medical, healing, detox, weight-loss, or nutrition claims.",
        stems = mapOf(
            "en" to listOf("\\bgood for (your )?(heart|digestion|skin|health|immune\\w*|body)\\b", "\\bhealthy\\b", "\\bheal\\w*", "\\bcure\\w*", "\\bdetox\\w*", "\\bdiet\\b", "\\bweight[- ]loss\\b", "\\bnutrit\\w*", "\\bmedicin\\w*"),
            "vi" to listOf("\\btốt cho sức khỏe\\b", "\\bsức khỏe\\b", "\\bgiảm cân\\b", "\\bchữa\\b", "\\bthải độc\\b", "\\bdinh dưỡng\\b"),
        ),
        substrings = mapOf(
            "ko" to listOf("건강", "몸에 좋", "다이어트", "치유", "해독", "디톡스", "영양"),
            "ja" to listOf("健康", "体にいい", "ダイエット", "治", "デトックス", "栄養"),
            "zh" to listOf("健康", "养生", "養生", "减肥", "減肥", "治", "排毒", "营养", "營養"),
            "th" to listOf("สุขภาพ", "ลดน้ำหนัก", "รักษา", "ดีท็อกซ์", "โภชนาการ"),
        ),
    ),
    PLACE(
        promptRule = "Never mention any restaurant, shop, store, branch, place name, address, or location.",
        stems = mapOf(
            "en" to listOf("\\brestaurants?\\b", "\\bshops?\\b", "\\bstores?\\b", "\\bbranch(es)?\\b", "\\bcaf[eé]s?\\b", "\\bdiners?\\b", "\\bstalls?\\b", "\\beater(y|ies)\\b"),
            "vi" to listOf("\\bquán\\b", "\\bnhà hàng\\b", "\\bcửa hàng\\b", "\\bchi nhánh\\b"),
        ),
        substrings = mapOf(
            "ko" to listOf("식당", "가게", "매장", "지점", "음식점", "본점"),
            "ja" to listOf("店", "レストラン", "食堂", "支店"),
            "zh" to listOf("餐厅", "餐廳", "店", "分店", "小吃摊"),
            "th" to listOf("ร้าน", "ภัตตาคาร", "สาขา"),
        ),
    ),
    PHOTO(
        promptRule = "Never mention photos or pictures.",
        stems = mapOf(
            "en" to listOf("\\bphotos?\\b", "\\bpictures?\\b", "\\bpics?\\b", "\\bselfies?\\b"),
            "vi" to listOf("\\bảnh\\b", "\\bhình\\b", "\\bchụp\\b"),
        ),
        substrings = mapOf(
            "ko" to listOf("사진", "셀카"),
            "ja" to listOf("写真"),
            "zh" to listOf("照片", "相片", "拍照"),
            "th" to listOf("รูป", "ภาพ"),
        ),
    ),
    ;

    fun patterns(): List<Regex> =
        stems.values.flatten().map { Regex(it, RegexOption.IGNORE_CASE) } +
            substrings.values.flatten().map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }

    fun terms(language: String): List<String> = stems[language] ?: substrings[language] ?: emptyList()

    companion object {
        val LANGUAGES: Set<String> = entries.flatMap { it.stems.keys + it.substrings.keys }.toSet()
    }
}
