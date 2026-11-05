package com.kbap.api.scan

object NotMenuFilter {
    private val OPTION_SUFFIX = Regex("(추가|사리|변경|곱빼기|소스|토핑|딥)$")
    private val OPTION_PREFIX = Regex("^(추가|곁들임)")
    private val EXACT_WORDS = setOf(
        "고기", "계란", "감자", "야채", "소고기", "한우", "뼈", "치즈", "고수", "공기밥", "밥",
        "세트", "셋트", "셋트메뉴", "세트메뉴", "매장세트",
    )

    fun isNotMenu(koreanName: String): Boolean {
        val name = koreanName.replace(" ", "")
        if (name.isEmpty()) return false
        return name in EXACT_WORDS || OPTION_SUFFIX.containsMatchIn(name) || OPTION_PREFIX.containsMatchIn(name)
    }
}
