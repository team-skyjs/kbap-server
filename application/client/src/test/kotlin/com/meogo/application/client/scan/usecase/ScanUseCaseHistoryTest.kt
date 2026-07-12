package com.meogo.application.client.scan.usecase

import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.application.client.member.FakeMemberRankingRepository
import com.meogo.application.client.scan.dto.ScanInput
import com.meogo.application.client.scan.dto.ScanItemInput
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.food.Food
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodContentStatus
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private class HistoryFakeFoodRepository(
    private val readyFoods: Map<String, Food>,
) : FoodRepository {
    private var nextId = 900L

    override fun findById(id: Long): Food? = null

    override fun findFoodPage(cursor: Long?, size: Int): List<Food> = emptyList()

    override fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> =
        emptyList()

    override fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> =
        readyFoods.filterKeys { it in keys }

    override fun createIncomplete(koreanNames: Set<String>): Map<String, Food> =
        koreanNames.associateWith { koreanName ->
            Food.reconstitute(
                id = nextId++,
                content = FoodContent(
                    name = LocalizedText(korean = koreanName),
                    description = LocalizedText(korean = Food.PLACEHOLDER_DESCRIPTION),
                ),
                imageRef = null,
                spiciness = FoodSpiciness(0),
                avoidanceSubstances = emptyList(),
                contentStatus = FoodContentStatus.INCOMPLETE,
            )
        }

    override fun findRandomReady(size: Int): List<Food> = emptyList()

    override fun findAllReadyByIds(ids: List<Long>): List<Food> = emptyList()

}

private class HistoryFakeAvoidedProvider : AvoidedSubstanceProvider {
    override fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> = emptySet()
}

private class HistoryFakeInterpreter : ScannedNameInterpreter {
    override fun interpret(texts: List<String>): List<InterpretedName> =
        texts.map { InterpretedName.StandardName(it) }
}

class ScanUseCaseHistoryTest : BehaviorSpec({
    fun readyFood(id: Long, koreanName: String) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = koreanName),
            description = LocalizedText(korean = "설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = emptyList(),
    )

    fun useCase(
        foods: Map<String, Food>,
        historyRepository: FakeScanHistoryRepository,
        rankingRepository: FakeMemberRankingRepository = FakeMemberRankingRepository(),
    ) = ScanUseCase(
        foodRepository = HistoryFakeFoodRepository(foods),
        avoidedSubstanceProvider = HistoryFakeAvoidedProvider(),
        interpreter = HistoryFakeInterpreter(),
        scanHistoryRepository = historyRepository,
        memberRankingRepository = rankingRepository,
    )

    fun input(memberId: Long, vararg names: String) = ScanInput(
        items = names.mapIndexed { index, name -> ScanItemInput(idx = index, rawMenuName = name) },
        memberId = memberId,
    )

    given("회원의 메뉴 스캔") {
        `when`("READY 음식에 매칭되는 메뉴를 스캔하면") {
            then("회원별 스캔 이력이 저장된다") {
                val history = FakeScanHistoryRepository()
                val uc = useCase(mapOf("김치찌개" to readyFood(7L, "김치찌개")), history)

                uc.assessMenuBoard(input(11L, "김치찌개"))

                history.saved.map { it.memberId to it.foodId } shouldContainExactly listOf(11L to 7L)
            }
        }

        `when`("같은 READY 음식이 여러 항목으로 매칭되면") {
            then("이력은 음식당 1건만 저장된다") {
                val history = FakeScanHistoryRepository()
                val uc = useCase(mapOf("김치찌개" to readyFood(7L, "김치찌개")), history)

                uc.assessMenuBoard(input(11L, "김치찌개", "김치찌개"))

                history.saved.map { it.foodId } shouldContainExactly listOf(7L)
            }
        }

        `when`("매칭 결과가 조사 대기(INCOMPLETE) 음식이면") {
            then("이력을 저장하지 않는다") {
                val history = FakeScanHistoryRepository()
                val uc = useCase(emptyMap(), history)

                uc.assessMenuBoard(input(11L, "처음보는찌개"))

                history.saveAllCallCount shouldBe 0
            }
        }

        `when`("READY 매칭과 INCOMPLETE 매칭이 섞여 있으면") {
            then("READY 음식만 이력으로 저장된다") {
                val history = FakeScanHistoryRepository()
                val uc = useCase(mapOf("비빔밥" to readyFood(30L, "비빔밥")), history)

                uc.assessMenuBoard(input(11L, "비빔밥", "처음보는찌개"))

                history.saved.map { it.memberId to it.foodId } shouldContainExactly listOf(11L to 30L)
            }
        }
    }

    given("랭킹 스캔 횟수 집계") {
        `when`("메뉴판 한 장에 여러 음식이 매칭되면") {
            then("스캔 횟수는 메뉴판 단위로 1회만 오른다") {
                val ranking = FakeMemberRankingRepository()
                val uc = useCase(
                    mapOf("김치찌개" to readyFood(7L, "김치찌개"), "비빔밥" to readyFood(8L, "비빔밥")),
                    FakeScanHistoryRepository(),
                    ranking,
                )

                uc.assessMenuBoard(input(11L, "김치찌개", "비빔밥"))

                ranking.scanCountOf(11L) shouldBe 1
            }
        }

        `when`("같은 회원이 메뉴판을 두 번 스캔하면") {
            then("스캔 횟수가 2가 된다") {
                val ranking = FakeMemberRankingRepository()
                val uc = useCase(mapOf("김치찌개" to readyFood(7L, "김치찌개")), FakeScanHistoryRepository(), ranking)

                uc.assessMenuBoard(input(11L, "김치찌개"))
                uc.assessMenuBoard(input(11L, "김치찌개"))

                ranking.scanCountOf(11L) shouldBe 2
            }
        }

        `when`("매칭된 음식이 하나도 없으면") {
            then("스캔 횟수는 그래도 1회 오른다") {
                val ranking = FakeMemberRankingRepository()
                val uc = useCase(emptyMap(), FakeScanHistoryRepository(), ranking)

                uc.assessMenuBoard(input(11L, "처음보는찌개"))

                ranking.scanCountOf(11L) shouldBe 1
            }
        }

        `when`("다른 회원이 스캔하면") {
            then("자기 횟수만 오른다") {
                val ranking = FakeMemberRankingRepository()
                val uc = useCase(mapOf("김치찌개" to readyFood(7L, "김치찌개")), FakeScanHistoryRepository(), ranking)

                uc.assessMenuBoard(input(11L, "김치찌개"))
                uc.assessMenuBoard(input(99L, "김치찌개"))

                ranking.scanCountOf(11L) shouldBe 1
                ranking.scanCountOf(99L) shouldBe 1
            }
        }
    }
})
