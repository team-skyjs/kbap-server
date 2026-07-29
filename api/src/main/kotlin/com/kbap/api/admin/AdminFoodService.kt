package com.kbap.api.admin

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.util.KoreanMenuNameNormalizer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminFoodService(
    private val foodRepository: FoodJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun seedIncomplete(koreanNames: Set<String>): SeedIncompleteResult {
        val names = koreanNames
            .map { KoreanMenuNameNormalizer.matchKey(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (names.isEmpty()) return SeedIncompleteResult(requested = 0, created = 0, skipped = 0)

        val existing = foodRepository.findByKoreanNameIn(names).map { it.koreanName }.toSet()
        val newNames = names - existing
        val created = if (newNames.isEmpty()) 0 else upsertAndResolve(newNames).size
        return SeedIncompleteResult(
            requested = names.size,
            created = created,
            skipped = names.size - created,
        )
    }

    private fun upsertAndResolve(koreanNames: Set<String>): Map<String, Food> {
        foodRepository.upsertIncomplete(koreanNames.map { Food.incomplete(it) })

        val resolved = foodRepository.findByKoreanNameIn(koreanNames).associateBy { it.koreanName }
        val unresolved = koreanNames - resolved.keys
        if (unresolved.isNotEmpty()) {
            log.warn("미완성 음식 등록 누락 — 소프트 삭제된 동명 음식이 있습니다: {}", unresolved)
        }
        return resolved
    }
}
