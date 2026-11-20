package com.kbap.api.scan

import com.kbap.api.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.util.KoreanMenuNameNormalizer
import org.springframework.stereotype.Service

enum class MenuMatchSource { EXACT_NAME, BASE_NAME, NOT_MENU, VECTOR, PENDING_AI_ESTIMATE }

data class MenuMatch(val source: MenuMatchSource, val food: Food? = null, val substitutedFromFoodId: Long? = null) {
    val substituted: Boolean get() = source == MenuMatchSource.VECTOR
}

@Service
class MenuMatchingService(
    private val foodService: FoodService,
    private val fallbackPolicies: List<MenuFallbackPolicy>,
) {
    fun matchMenus(menus: List<ExtractedMenu>, allowVectorMatch: Boolean): Map<String, MenuMatch> {
        // 1. 원문·기본 음식명의 검색 키 수집 및 중복 제거
        val scannedNamesByKey = linkedMapOf<String, String>()
        val baseKeyByScannedKey = mutableMapOf<String, String>()
        for (menu in menus) {
            val scannedKey = KoreanMenuNameNormalizer.matchKey(menu.koreanName)
            if (scannedKey.isBlank()) continue
            scannedNamesByKey.putIfAbsent(scannedKey, menu.koreanName)
            val baseName = menu.baseName ?: continue
            baseKeyByScannedKey.putIfAbsent(scannedKey, KoreanMenuNameNormalizer.matchKey(baseName))
        }

        // 2. 원문·기본 음식명 DB 일괄 조회
        val existingFoodsByKey = foodService.getFoodsByKoreanNames(scannedNamesByKey.keys + baseKeyByScannedKey.values.filter { it.isNotBlank() })
        val matches = mutableMapOf<String, MenuMatch>()
        val unmatchedNamesByKey = mutableMapOf<String, String>()
        // 3. 원문 일치 우선 → 기본 음식명 일치 확인 → 미매칭 항목의 비메뉴 여부 확인
        for ((scannedKey, scannedName) in scannedNamesByKey) {
            val foodByScannedName = existingFoodsByKey[scannedKey]
            val foodByBaseName = existingFoodsByKey[baseKeyByScannedKey[scannedKey]]
            when {
                foodByScannedName?.isReady() == true -> matches[scannedKey] = MenuMatch(MenuMatchSource.EXACT_NAME, foodByScannedName)
                foodByBaseName?.isReady() == true -> matches[scannedKey] = MenuMatch(MenuMatchSource.BASE_NAME, foodByBaseName, foodByScannedName?.id)
                NotMenuFilter.isNotMenu(scannedName) -> matches[scannedKey] = MenuMatch(MenuMatchSource.NOT_MENU)
                else -> unmatchedNamesByKey[scannedKey] = scannedName
            }
        }

        // 4. 신규 이름만 미완성 음식으로 등록 · 원래 음식 ID 보존
        val namesMissingFromDb = unmatchedNamesByKey - existingFoodsByKey.keys
        val foodsForMissingNames = foodService.createIncomplete(namesMissingFromDb)
        val menusAwaitingFallback = mutableMapOf<String, UnmatchedMenu>()
        for ((scannedKey, scannedName) in unmatchedNamesByKey) {
            val foodByScannedName = existingFoodsByKey[scannedKey] ?: foodsForMissingNames[scannedKey]
            menusAwaitingFallback[scannedKey] = UnmatchedMenu(scannedKey, scannedName, foodByScannedName)
        }
        // 5. 미처리 메뉴에만 다음 정책 적용
        for (policy in fallbackPolicies) {
            if (menusAwaitingFallback.isEmpty()) break
            val policyMatches = policy.matchUnmatchedMenus(menusAwaitingFallback.values, allowVectorMatch)
            matches.putAll(policyMatches)
            menusAwaitingFallback.keys.removeAll(policyMatches.keys)
        }
        return matches
    }
}
