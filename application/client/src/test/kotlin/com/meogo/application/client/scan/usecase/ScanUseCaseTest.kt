package com.meogo.application.client.scan.usecase

import com.meogo.application.client.scan.dto.ScanItemInput
import com.meogo.application.client.scan.dto.ScanInput
import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class FakeFoodRepository(private val readyFoods: Map<String, Food>) : FoodRepository {
    val createdIncomplete = mutableListOf<String>()
    var createIncompleteCallCount = 0
        private set
    private var nextId = 100L

    override fun findById(id: Long): Food? = null
    override fun findFoodPage(cursor: Long?, size: Int): List<Food> = emptyList()

    override fun searchFoodPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> =
        emptyList()

    override fun findByKoreanMatchKeys(keys: Set<String>): Map<String, Food> =
        readyFoods.filterKeys { it in keys }

    override fun createIncomplete(koreanNames: Set<String>): Map<String, Food> {
        createIncompleteCallCount++
        createdIncomplete += koreanNames
        return koreanNames.associateWith { koreanName ->
            Food.reconstitute(
                id = nextId++,
                content = FoodContent(
                    name = LocalizedText(korean = koreanName),
                    description = LocalizedText(korean = Food.PLACEHOLDER_DESCRIPTION),
                ),
                imageRef = null,
                spiciness = FoodSpiciness(0),
                avoidanceSubstances = emptyList(),
                contentStatus = com.meogo.core.food.FoodContentStatus.INCOMPLETE,
            )
        }
    }

    override fun findRandomReady(size: Int): List<Food> = emptyList()

    override fun findAllReadyByIds(ids: List<Long>): List<Food> = emptyList()

}

private class ScanFakeAvoidedProvider : AvoidedSubstanceProvider {
    override fun avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode> = setOf(AvoidanceSubstanceCode.SOY)
}


private class FakeInterpreter(private val results: List<InterpretedName>) : ScannedNameInterpreter {
    var callCount = 0
        private set

    override fun interpret(texts: List<String>): List<InterpretedName> {
        callCount++
        return results
    }
}

private class ThrowingInterpreter : ScannedNameInterpreter {
    override fun interpret(texts: List<String>): List<InterpretedName> = throw RuntimeException("LLM 장애")
}

class ScanUseCaseTest : BehaviorSpec({
    fun item(idx: Int, name: String) = ScanItemInput(idx = idx, rawMenuName = name)

    fun readyFood(id: Long, koreanName: String, substance: Pair<String, Int>? = null) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = koreanName),
            description = LocalizedText(korean = "설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = substance?.let {
            listOf(FoodAvoidanceSubstance(AvoidanceSubstanceCodeRef(it.first), it.second))
        } ?: emptyList(),
    )

    fun useCase(
        foods: Map<String, Food> = emptyMap(),
        interpreter: ScannedNameInterpreter,
        foodRepo: FakeFoodRepository = FakeFoodRepository(foods),
    ) = ScanUseCase(
        foodRepository = foodRepo,
        avoidedSubstanceProvider = ScanFakeAvoidedProvider(),
        interpreter = interpreter,
        scanHistoryRepository = FakeScanHistoryRepository(),
    )

    fun translatedFood(id: Long, koreanName: String, english: String) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = koreanName, translations = mapOf(LanguageCode.EN to english)),
            description = LocalizedText(korean = "설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = emptyList(),
    )

    given("정제 서비스가 정상일 때") {
        `when`("표준명이 완성된 저장 음식과 일치하면") {
            then("MATCHED 이고 산출된 위험도를 반환한다") {
                val uc = useCase(
                    foods = mapOf("김치찌개" to readyFood(7L, "김치찌개", "SOY" to 100)),
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("김치찌개"))),
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개 kimchi jjigae"))))

                result.items.first().matched shouldBe true
                result.items.first().riskLevel shouldBe RiskLevel.DANGER.name
                result.degraded shouldBe false
            }
        }

        `when`("표준명이 저장에 없으면") {
            then("food 에 미완성으로 등록하고 PENDING·UNKNOWN 으로 응답한다") {
                val foodRepo = FakeFoodRepository(emptyMap())
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("우주라면"))),
                    foodRepo = foodRepo,
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "우주라면"))))

                foodRepo.createdIncomplete shouldBe listOf("우주라면")
                result.items.first().matched shouldBe false
                result.items.first().riskLevel shouldBe RiskLevel.UNKNOWN.name
                result.items.first().foodId shouldBe 100L
            }
        }

        `when`("매칭되지 않은 항목이 여러 개면") {
            then("이름을 모아 저장소를 한 번만 호출한다(항목마다 반복 저장하지 않는다)") {
                val foodRepo = FakeFoodRepository(emptyMap())
                val uc = useCase(
                    interpreter = FakeInterpreter(
                        listOf(
                            InterpretedName.StandardName("우주라면"),
                            InterpretedName.StandardName("탕후루"),
                            InterpretedName.StandardName("우주라면"),
                        ),
                    ),
                    foodRepo = foodRepo,
                )

                val result = uc.assessMenuBoard(
                    ScanInput(memberId = 1L, items = listOf(item(0, "우주라면"), item(1, "탕후루"), item(2, "우주라면 space"))),
                )

                foodRepo.createIncompleteCallCount shouldBe 1
                foodRepo.createdIncomplete.toSet() shouldBe setOf("우주라면", "탕후루")
                result.items.map { it.matched }.toSet() shouldBe setOf(false)
                val byItem = result.items.associate { it.idx to it.foodId }
                byItem.getValue(0) shouldBe byItem.getValue(2)
            }
        }

        `when`("모든 항목이 저장 음식과 매칭되면") {
            then("저장소에 빈 집합을 넘겨 새 음식을 만들지 않는다") {
                val foodRepo = FakeFoodRepository(mapOf("김치찌개" to readyFood(7L, "김치찌개")))
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("김치찌개"))),
                    foodRepo = foodRepo,
                )

                uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개"))))

                foodRepo.createdIncomplete shouldBe emptyList()
            }
        }

        `when`("이미 미완성으로 등록된 음식을 다시 스캔하면") {
            then("중복 생성 없이 PENDING·UNKNOWN 으로 응답한다") {
                val incomplete = Food.reconstitute(
                    id = 55L,
                    content = FoodContent(
                        name = LocalizedText(korean = "우주라면"),
                        description = LocalizedText(korean = Food.PLACEHOLDER_DESCRIPTION),
                    ),
                    imageRef = null,
                    spiciness = FoodSpiciness(0),
                    avoidanceSubstances = emptyList(),
                    contentStatus = com.meogo.core.food.FoodContentStatus.INCOMPLETE,
                )
                val foodRepo = FakeFoodRepository(mapOf("우주라면" to incomplete))
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("우주라면"))),
                    foodRepo = foodRepo,
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "우주라면"))))

                foodRepo.createdIncomplete shouldBe emptyList()
                result.items.first().matched shouldBe false
                result.items.first().riskLevel shouldBe RiskLevel.UNKNOWN.name
            }
        }

        `when`("LLM 이 NOT_FOOD 로 판정하면") {
            then("결과에서 제외되고 food 를 만들지 않는다") {
                val foodRepo = FakeFoodRepository(emptyMap())
                val uc = useCase(
                    interpreter = FakeInterpreter(listOf(InterpretedName.NotFood)),
                    foodRepo = foodRepo,
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "원산지 중국"))))

                result.items shouldBe emptyList()
                foodRepo.createdIncomplete shouldBe emptyList()
            }
        }

        `when`("한글이 전혀 없는 항목이면") {
            then("LLM 을 거치지 않고 결과에서 제외한다") {
                val interpreter = FakeInterpreter(emptyList())
                val uc = useCase(interpreter = interpreter)

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "MacBook Air F9"))))

                result.items shouldBe emptyList()
                interpreter.callCount shouldBe 0
            }
        }

        `when`("여러 항목을 제출하면") {
            then("스캔당 LLM 을 1번만 호출한다") {
                val interpreter = FakeInterpreter(
                    listOf(InterpretedName.StandardName("김치찌개"), InterpretedName.NotFood),
                )
                val uc = useCase(
                    foods = mapOf("김치찌개" to readyFood(7L, "김치찌개")),
                    interpreter = interpreter,
                )

                uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개"), item(1, "원산지 중국"))))

                interpreter.callCount shouldBe 1
            }
        }

        `when`("한 스캔 안에서 같은 표준명이 여러 항목으로 나오면") {
            then("미완성 음식을 1번만 생성한다") {
                val foodRepo = FakeFoodRepository(emptyMap())
                val uc = useCase(
                    interpreter = FakeInterpreter(
                        listOf(InterpretedName.StandardName("우주라면"), InterpretedName.StandardName("우주라면")),
                    ),
                    foodRepo = foodRepo,
                )

                uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "우주라면"), item(1, "우주 라면"))))

                foodRepo.createdIncomplete shouldBe listOf("우주라면")
            }
        }
    }

    given("정제 서비스 호출이 실패할 때(폴백)") {
        `when`("interpreter 가 예외를 던지면") {
            then("아는 메뉴는 MATCHED, 나머지는 food 생성 없이 PENDING 으로 강등한다") {
                val foodRepo = FakeFoodRepository(mapOf("김치찌개" to readyFood(7L, "김치찌개")))
                val uc = useCase(
                    interpreter = ThrowingInterpreter(),
                    foodRepo = foodRepo,
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개"), item(1, "우주라면 space"))))

                result.degraded shouldBe true
                val items = result.items
                items.first { it.idx == 0 }.matched shouldBe true
                items.first { it.idx == 1 }.matched shouldBe false
                items.first { it.idx == 1 }.foodId shouldBe null
                foodRepo.createdIncomplete shouldBe emptyList()
            }
        }

        `when`("interpreter 가 요청보다 적은 개수를 반환하면") {
            then("결과를 신뢰하지 않고 정규화 exact 매치 폴백으로 degraded=true 를 알린다") {
                val uc = useCase(
                    foods = mapOf("김치찌개" to readyFood(7L, "김치찌개")),
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("무시됨"))),
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개"), item(1, "우주라면"))))

                result.degraded shouldBe true
                val items = result.items
                items.first { it.idx == 0 }.matched shouldBe true
                items.first { it.idx == 1 }.matched shouldBe false
            }
        }
    }

    given("응답 메뉴명 — 현재는 한국어 고정(회원 언어 설정 연동 전)") {
        `when`("매칭된 음식에 en 번역이 있어도") {
            then("name 을 한국어로 내리고 koreanName 과 같다") {
                val uc = useCase(
                    foods = mapOf("김치찌개" to translatedFood(7L, "김치찌개", "Kimchi Stew")),
                    interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("김치찌개"))),
                )

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "김치찌개 kimchi"))))

                result.items.first().name shouldBe "김치찌개"
                result.items.first().koreanName shouldBe "김치찌개"
            }
        }

        `when`("미완성으로 새로 등록된 음식이면") {
            then("표준명을 name·koreanName 에 함께 담는다") {
                val uc = useCase(interpreter = FakeInterpreter(listOf(InterpretedName.StandardName("우주라면"))))

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "우주라면"))))

                result.items.first().name shouldBe "우주라면"
                result.items.first().koreanName shouldBe "우주라면"
            }
        }

        `when`("폴백 중 아는 음식이 아니어서 판정이 불가하면") {
            then("서버가 아는 이름이 없으므로 name·koreanName 이 null 이다") {
                val uc = useCase(interpreter = ThrowingInterpreter())

                val result = uc.assessMenuBoard(ScanInput(memberId = 1L, items = listOf(item(0, "우주라면 space"))))

                result.items.first().foodId shouldBe null
                result.items.first().name shouldBe null
                result.items.first().koreanName shouldBe null
            }
        }
    }
})
