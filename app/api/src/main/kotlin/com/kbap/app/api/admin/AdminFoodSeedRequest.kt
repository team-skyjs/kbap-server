package com.kbap.app.api.admin

import com.kbap.core.menu.KoreanMenuNameNormalizer
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class AdminFoodSeedRequest(
    @field:NotNull
    @field:Size(max = MAX_SEED_NAMES, message = "koreanNames 는 최대 $MAX_SEED_NAMES 건까지 제출할 수 있습니다")
    val koreanNames: List<String>? = null,
) {
    fun toKoreanNames(): Set<String> {
        // korean_name 정규화 불변식(NFC·한글만) — 스캔 입구(ScanService.resolveFoods)와 동일 기준
        val names = koreanNames.orEmpty()
            .map { KoreanMenuNameNormalizer.matchKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        require(names.all { it.length <= KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH }) {
            "koreanNames 항목은 ${KoreanMenuNameNormalizer.MAX_MENU_NAME_LENGTH}자를 넘을 수 없습니다"
        }
        return names
    }

    companion object {
        const val MAX_SEED_NAMES = 500
    }
}
