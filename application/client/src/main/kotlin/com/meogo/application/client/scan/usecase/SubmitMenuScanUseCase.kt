package com.meogo.application.client.scan.usecase

import com.meogo.application.client.scan.dto.SubmitMenuScanInput
import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.menu.KoreanMenuNameNormalizer
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.core.scan.BoundingBox
import com.meogo.core.scan.MenuItemMatch
import com.meogo.core.scan.MenuScan
import com.meogo.core.scan.MenuScanRepository
import com.meogo.core.scan.PendingMenuRepository
import com.meogo.core.scan.ScannedMenuItem
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class SubmitMenuScanUseCase(
    private val menuScanRepository: MenuScanRepository,
    private val foodRepository: FoodRepository,
    private val pendingMenuRepository: PendingMenuRepository,
    private val riskAssessor: MockCyclingRiskAssessor,
    @Autowired(required = false)
    private val interpreter: ScannedNameInterpreter? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(input: SubmitMenuScanInput): SubmitMenuScanResult {
        val matches = resolveMatches(input)

        val items = input.items.mapIndexed { index, item ->
            val box = item.boundingBox
            ScannedMenuItem(
                itemId = item.itemId,
                rawMenuName = item.rawMenuName,
                boundingBox = BoundingBox(x = box.x, y = box.y, width = box.width, height = box.height),
                assessment = riskAssessor.assess(index, item.rawMenuName),
                match = matches[index],
            )
        }

        return MenuScan.create(MenuScan.CreationSpec(items))
            .let(menuScanRepository::save)
            .let(SubmitMenuScanResult::from)
    }

    private fun resolveMatches(input: SubmitMenuScanInput): List<MenuItemMatch> {
        val keys = input.items.map { KoreanMenuNameNormalizer.matchKey(it.rawMenuName) }
        val interpreted = interpretTargets(input, keys)

        val resolutions = input.items.mapIndexed { index, item ->
            when {
                keys[index].isBlank() -> Resolution(MenuItemMatch.NotFood)
                interpreted == null -> matchOrPending(keys[index], item.rawMenuName)
                else -> resolveInterpreted(interpreted.getValue(index))
            }
        }

        resolutions.mapNotNull { it.nameToEnqueue }.distinct().forEach(pendingMenuRepository::enqueue)
        return resolutions.map { it.match }
    }

    private fun resolveInterpreted(interpretedName: InterpretedName): Resolution =
        when (interpretedName) {
            is InterpretedName.StandardName ->
                matchOrPending(KoreanMenuNameNormalizer.matchKey(interpretedName.korean), interpretedName.korean)
            InterpretedName.NotFood -> Resolution(MenuItemMatch.NotFood)
        }

    private fun matchOrPending(lookupKey: String, enqueueName: String): Resolution {
        val foodId = foodRepository.findFoodIdByKoreanMatchKey(lookupKey)
        return if (foodId != null) {
            Resolution(MenuItemMatch.Matched(foodId))
        } else {
            Resolution(MenuItemMatch.Pending, nameToEnqueue = enqueueName)
        }
    }

    private data class Resolution(val match: MenuItemMatch, val nameToEnqueue: String? = null)

    private fun interpretTargets(
        input: SubmitMenuScanInput,
        keys: List<String>,
    ): Map<Int, InterpretedName>? {
        if (interpreter == null) return null
        val targetIndexes = keys.indices.filter { keys[it].isNotBlank() }
        if (targetIndexes.isEmpty()) return null

        val texts = targetIndexes.map { input.items[it].rawMenuName }
        return try {
            val interpreted = interpreter.interpret(texts)
            require(interpreted.size == targetIndexes.size) {
                "정제 결과 개수(${interpreted.size})가 요청(${targetIndexes.size})과 다릅니다"
            }
            targetIndexes.zip(interpreted).toMap()
        } catch (e: Exception) {
            log.warn("정제 서비스 호출 실패 — 정규화 exact 매치 폴백", e)
            null
        }
    }
}
