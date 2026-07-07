package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.BrowseMenusInput
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class BrowseMenusUseCaseTest : BehaviorSpec({
    fun menuFood(id: Long) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = "메뉴-$id"),
            description = LocalizedText(korean = "메뉴-$id 설명"),
        ),
        imageRef = "menu-$id.png",
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = emptyList(),
    )

    fun descendingFoods(count: Int): List<Food> =
        (count downTo 1).map { menuFood(it.toLong()) }

    fun useCase(foodRepository: FoodRepository) = BrowseMenusUseCase(
        foodRepository,
        LanguageResolver(),
        BrowseFakeAvoidedSubstanceProvider(emptySet()),
        BrowseFakeAvoidanceSubstanceRepository(emptyList()),
    )

    fun avoidedRef(code: AvoidanceSubstanceCode, probability: Int) =
        FoodAvoidanceSubstance(
            substanceCode = AvoidanceSubstanceCodeRef(code.name),
            inclusionProbability = probability,
        )

    fun richFood(
        id: Long,
        nameTranslations: Map<LanguageCode, String> = emptyMap(),
        substances: List<FoodAvoidanceSubstance> = emptyList(),
    ) = Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = "메뉴-$id", translations = nameTranslations),
            description = LocalizedText(korean = "메뉴-$id 설명"),
        ),
        imageRef = "menu-$id.png",
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = substances,
    )

    fun catalogSubstance(code: AvoidanceSubstanceCode) =
        AvoidanceSubstance.reconstitute(
            id = code.ordinal.toLong() + 1,
            code = code,
            name = LocalizedText(korean = code.label),
        )

    fun browseUseCase(
        foods: List<Food>,
        avoidedCodes: Set<AvoidanceSubstanceCode>,
        catalog: List<AvoidanceSubstance>,
    ) = BrowseMenusUseCase(
        BrowseFakeFoodRepository(foods),
        LanguageResolver(),
        BrowseFakeAvoidedSubstanceProvider(avoidedCodes),
        BrowseFakeAvoidanceSubstanceRepository(catalog),
    )

    given("메뉴 목록 조회 유스케이스 — 최신순 keyset 페이지네이션") {
        `when`("포트가 페이지 크기+1(21)개를 반환하면") {
            then("다음 페이지가 있다고 판정하고(hasNext=true) 20개만 담으며 nextCursor 는 마지막 항목 foodId 다") {
                val foodRepository = BrowseFakeFoodRepository(descendingFoods(21))

                val result = useCase(foodRepository).browse(BrowseMenusInput(cursor = null, lang = null))

                result.hasNext shouldBe true
                result.items.size shouldBe 20
                result.nextCursor shouldBe result.items.last().foodId
            }
        }

        `when`("cursor 를 지정해 다음 페이지를 조회하면") {
            then("포트를 cursor·페이지 크기+1(21)로 호출하고 반환 순서(최신순)를 유지한다") {
                val foodRepository = BrowseFakeFoodRepository(descendingFoods(21))

                val result = useCase(foodRepository).browse(BrowseMenusInput(cursor = 100L, lang = null))

                foodRepository.requestedCursor shouldBe 100L
                foodRepository.requestedSize shouldBe 21
                result.items.map { it.foodId } shouldBe (21 downTo 2).map { it.toLong() }
            }
        }
    }

    given("메뉴 목록 조회 — 경계: 빈 결과·마지막 페이지 (US3)") {
        `when`("포트가 0건을 반환하면 (커서가 최소 id 이하 등)") {
            then("items 는 빈 리스트이고 hasNext=false·nextCursor=null 이다") {
                val result = useCase(BrowseFakeFoodRepository(emptyList()))
                    .browse(BrowseMenusInput(cursor = 1L, lang = null))

                result.items shouldBe emptyList()
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }

        `when`("포트가 페이지 크기와 정확히 같은 20건을 반환하면") {
            then("마지막 페이지로 보고 hasNext=false·nextCursor=null 이며 20개를 모두 담는다") {
                val result = useCase(BrowseFakeFoodRepository(descendingFoods(20)))
                    .browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.size shouldBe 20
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }

        `when`("포트가 페이지 크기보다 적은 5건을 반환하면") {
            then("마지막 페이지로 보고 hasNext=false·nextCursor=null 이며 5개를 담는다") {
                val result = useCase(BrowseFakeFoodRepository(descendingFoods(5)))
                    .browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.size shouldBe 5
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }
    }

    given("메뉴 목록 조회 — food별 종합 위험도 (사용자 회피 ∩ 성분 포함확률 최악값)") {
        `when`("회피 성분을 포함한 food(SOY100·MILK30)와 미포함 food(WHEAT100)가 섞여 있으면") {
            then("포함 food 는 포함확률 규칙대로(DANGER·CAUTION), 미포함 food 는 SAFE 로 각각 판정한다") {
                val foodDanger = richFood(10, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100)))
                val foodCaution = richFood(11, substances = listOf(avoidedRef(AvoidanceSubstanceCode.MILK, 30)))
                val foodSafe = richFood(12, substances = listOf(avoidedRef(AvoidanceSubstanceCode.WHEAT, 100)))

                val result = browseUseCase(
                    foods = listOf(foodDanger, foodCaution, foodSafe),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.MILK),
                    catalog = listOf(
                        catalogSubstance(AvoidanceSubstanceCode.SOY),
                        catalogSubstance(AvoidanceSubstanceCode.MILK),
                        catalogSubstance(AvoidanceSubstanceCode.WHEAT),
                    ),
                ).browse(BrowseMenusInput(cursor = null, lang = null))

                val statusById = result.items.associate { it.foodId to it.overallRiskStatus }
                statusById[10L] shouldBe RiskLevel.DANGER
                statusById[11L] shouldBe RiskLevel.CAUTION
                statusById[12L] shouldBe RiskLevel.SAFE
            }
        }
    }

    given("메뉴 목록 조회 — 카탈로그 결측(소프트삭제) 성분은 위험도에 미반영 (상세와 동일 의미)") {
        `when`("food 가 SOY(100)를 포함하고 사용자가 SOY 를 회피하지만 카탈로그(findByCodes)에서 SOY 가 빠지면") {
            then("판정 대상에서 제외돼 overallRiskStatus 는 SAFE 다") {
                val result = browseUseCase(
                    foods = listOf(richFood(20, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100)))),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY),
                    catalog = emptyList(),
                ).browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.single().overallRiskStatus shouldBe RiskLevel.SAFE
            }
        }

        `when`("동일 food·회피 조건에서 카탈로그에 SOY 가 존재하면") {
            then("대비적으로 교집합이 걸려 overallRiskStatus 는 DANGER 로 반영된다") {
                val result = browseUseCase(
                    foods = listOf(richFood(20, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100)))),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY),
                    catalog = listOf(catalogSubstance(AvoidanceSubstanceCode.SOY)),
                ).browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.single().overallRiskStatus shouldBe RiskLevel.DANGER
            }
        }
    }

    given("메뉴 목록 조회 — 표시명 언어 지역화·폴백 (상세와 동일 규칙)") {
        `when`("lang=en 이고 en 번역이 있으면") {
            then("표시명을 영어로 조립한다") {
                val translated = richFood(30, nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"))

                val result = browseUseCase(listOf(translated), emptySet(), emptyList())
                    .browse(BrowseMenusInput(cursor = null, lang = "en"))

                result.items.single().name shouldBe "Kimchi Stew"
            }
        }

        `when`("lang 미지정(null)이면") {
            then("표시명을 한국어 원문으로 폴백한다") {
                val translated = richFood(30, nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"))

                val result = browseUseCase(listOf(translated), emptySet(), emptyList())
                    .browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.single().name shouldBe "메뉴-30"
            }
        }

        `when`("lang=en 이지만 해당 food 에 en 번역이 없으면") {
            then("표시명을 한국어로 폴백한다") {
                val untranslated = richFood(31)

                val result = browseUseCase(listOf(untranslated), emptySet(), emptyList())
                    .browse(BrowseMenusInput(cursor = null, lang = "en"))

                result.items.single().name shouldBe "메뉴-31"
            }
        }
    }

    given("메뉴 목록 조회 — 항목 숫자 foodId 정합 (상세 조회 식별자)") {
        `when`("food.id 를 가진 항목을 조회하면") {
            then("응답 항목 foodId 가 food.id 와 순서대로 일치한다") {
                val result = browseUseCase(listOf(richFood(42), richFood(7)), emptySet(), emptyList())
                    .browse(BrowseMenusInput(cursor = null, lang = null))

                result.items.map { it.foodId } shouldBe listOf(42L, 7L)
            }
        }
    }

    given("메뉴 목록 조회 — 위험도 계산의 카탈로그 조회는 페이지당 1회 (N+1 회피)") {
        `when`("성분을 가진 food 가 여러 개인 페이지를 조회하면") {
            then("avoidanceSubstanceRepository.findByCodes 는 정확히 1회만 호출된다") {
                val catalogRepository = BrowseFakeAvoidanceSubstanceRepository(
                    listOf(
                        catalogSubstance(AvoidanceSubstanceCode.SOY),
                        catalogSubstance(AvoidanceSubstanceCode.MILK),
                        catalogSubstance(AvoidanceSubstanceCode.WHEAT),
                    ),
                )
                val useCase = BrowseMenusUseCase(
                    BrowseFakeFoodRepository(
                        listOf(
                            richFood(1, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100))),
                            richFood(2, substances = listOf(avoidedRef(AvoidanceSubstanceCode.MILK, 100))),
                            richFood(3, substances = listOf(avoidedRef(AvoidanceSubstanceCode.WHEAT, 100))),
                        ),
                    ),
                    LanguageResolver(),
                    BrowseFakeAvoidedSubstanceProvider(setOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.MILK)),
                    catalogRepository,
                )

                useCase.browse(BrowseMenusInput(cursor = null, lang = null))

                catalogRepository.callCount shouldBe 1
            }
        }
    }
})

private class BrowseFakeFoodRepository(
    private val page: List<Food>,
) : FoodRepository {
    var requestedCursor: Long? = null
        private set
    var requestedSize: Int? = null
        private set

    override fun findByKoreanName(name: String): Food? = null

    override fun findMenuPage(cursor: Long?, size: Int): List<Food> {
        requestedCursor = cursor
        requestedSize = size
        return page.take(size)
    }
}

private class BrowseFakeAvoidedSubstanceProvider(
    private val codes: Set<AvoidanceSubstanceCode>,
) : AvoidedSubstanceProvider {
    override fun avoidedCodes() = codes
}

private class BrowseFakeAvoidanceSubstanceRepository(
    private val substances: List<AvoidanceSubstance>,
) : AvoidanceSubstanceRepository {
    var callCount = 0
        private set

    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> {
        callCount++
        return substances.filter { it.code in codes }
    }
}
