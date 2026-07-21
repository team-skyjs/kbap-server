package com.kbap.app.api.admin

import com.kbap.core.menu.KoreanMenuNameNormalizer
import jakarta.validation.constraints.NotNull

data class AdminFoodSeedRequest(
    @field:NotNull
    val koreanNames: List<String>? = null,
) {
    fun toKoreanNames(): Set<String> {
        val names = koreanNames.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        require(names.all { it.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH }) {
            "koreanNames 항목은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
        }
        return names
    }
}
