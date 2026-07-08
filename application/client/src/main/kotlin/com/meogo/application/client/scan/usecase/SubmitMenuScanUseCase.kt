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
        val targetIndexes = keys.indices.filter { keys[it].isNotBlank() }
        val interpreted = interpretTargets(input, targetIndexes)

        val enqueueNames = mutableListOf<String>()
        val matches = keys.indices.map { index ->
            val key = keys[index]
            when {
                key.isBlank() -> MenuItemMatch.NotFood
                interpreted != null -> resolveInterpreted(interpreted.getValue(index), enqueueNames)
                else -> matchOrPending(key, input.items[index].rawMenuName, enqueueNames)
            }
        }

        enqueueNames.distinct().forEach(pendingMenuRepository::enqueue)
        return matches
    }

    private fun interpretTargets(
        input: SubmitMenuScanInput,
        targetIndexes: List<Int>,
    ): Map<Int, InterpretedName>? {
        if (interpreter == null || targetIndexes.isEmpty()) return null
        return try {
            val texts = targetIndexes.map { input.items[it].rawMenuName }
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

    private fun resolveInterpreted(
        interpreted: InterpretedName,
        enqueueNames: MutableList<String>,
    ): MenuItemMatch =
        when (interpreted) {
            is InterpretedName.StandardName ->
                matchOrPending(KoreanMenuNameNormalizer.matchKey(interpreted.korean), interpreted.korean, enqueueNames)
            InterpretedName.NotFood -> MenuItemMatch.NotFood
        }

    private fun matchOrPending(
        lookupKey: String,
        enqueueName: String,
        enqueueNames: MutableList<String>,
    ): MenuItemMatch {
        val foodId = foodRepository.findFoodIdByKoreanMatchKey(lookupKey)
        return if (foodId != null) {
            MenuItemMatch.Matched(foodId)
        } else {
            enqueueNames += enqueueName
            MenuItemMatch.Pending
        }
    }
}
