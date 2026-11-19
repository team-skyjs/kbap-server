package com.kbap.api.scan

import com.kbap.common.domain.food.model.Food
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

data class UnmatchedMenu(val scannedKey: String, val scannedName: String, val foodByScannedName: Food?)

interface MenuFallbackPolicy {
    fun matchUnmatchedMenus(menus: Collection<UnmatchedMenu>, allowVectorMatch: Boolean): Map<String, MenuMatch>
}

@Component
@Order(1)
@ConditionalOnProperty(prefix = "kbap.scan.vector-substitution", name = ["enabled"], havingValue = "true")
class VectorMatchPolicy(private val similarFoodService: SimilarFoodService) : MenuFallbackPolicy {
    override fun matchUnmatchedMenus(menus: Collection<UnmatchedMenu>, allowVectorMatch: Boolean): Map<String, MenuMatch> {
        // 벡터 대체를 허용한 요청만 검색
        if (!allowVectorMatch) return emptyMap()
        val similarFoodsByName = similarFoodService.findSimilarFoods(menus.map { it.scannedName })
        val matches = mutableMapOf<String, MenuMatch>()
        for (menu in menus) {
            val similarFood = similarFoodsByName[menu.scannedName] ?: continue
            val originalFoodId = menu.foodByScannedName?.id
            matches[menu.scannedKey] = MenuMatch(MenuMatchSource.VECTOR, similarFood, originalFoodId?.takeIf { it != similarFood.id })
        }
        return matches
    }
}

@Component
@Order(2)
class AiEstimatePolicy : MenuFallbackPolicy {
    override fun matchUnmatchedMenus(menus: Collection<UnmatchedMenu>, allowVectorMatch: Boolean): Map<String, MenuMatch> {
        // AI 추정 대상 표시 · 실제 호출은 스캔 성공 기록 후 비동기 실행
        val matches = mutableMapOf<String, MenuMatch>()
        for (menu in menus) {
            matches[menu.scannedKey] = MenuMatch(MenuMatchSource.PENDING_AI_ESTIMATE, menu.foodByScannedName)
        }
        return matches
    }
}
