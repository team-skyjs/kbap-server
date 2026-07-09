package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.SearchMenusInput
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.kernel.risk.RiskLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class SearchMenusUseCaseTest : BehaviorSpec({
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

    fun descendingFoods(count: Int): List<Food> = (count downTo 1).map { menuFood(it.toLong()) }

    fun useCase(foodRepository: FoodRepository) = SearchMenusUseCase(
        foodRepository,
        LanguageResolver(),
        MenuSummaryAssembler(
            SearchFakeAvoidedSubstanceProvider(emptySet()),
            SearchFakeAvoidanceSubstanceRepository(emptyList()),
        ),
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

    fun searchUseCase(
        foods: List<Food>,
        avoidedCodes: Set<AvoidanceSubstanceCode>,
        catalog: List<AvoidanceSubstance>,
    ) = SearchMenusUseCase(
        SearchFakeFoodRepository(foods),
        LanguageResolver(),
        MenuSummaryAssembler(
            SearchFakeAvoidedSubstanceProvider(avoidedCodes),
            SearchFakeAvoidanceSubstanceRepository(catalog),
        ),
    )

    given("검색어 메뉴 조회 유스케이스 — 빈/공백 검색어 검증 (FR-011)") {
        `when`("검색어가 빈 문자열이면") {
            then("BLANK_SEARCH_KEYWORD 예외를 던지고 검색 포트를 호출하지 않는다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                val exception = shouldThrow<FoodException> {
                    useCase(foodRepository).search(SearchMenusInput(keyword = "", cursor = null, lang = null))
                }

                exception.errorCode shouldBe FoodErrorCode.BLANK_SEARCH_KEYWORD
                foodRepository.searchCallCount shouldBe 0
            }
        }

        `when`("검색어가 공백뿐이면") {
            then("BLANK_SEARCH_KEYWORD 예외를 던지고 검색 포트를 호출하지 않는다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                val exception = shouldThrow<FoodException> {
                    useCase(foodRepository).search(SearchMenusInput(keyword = "   ", cursor = null, lang = null))
                }

                exception.errorCode shouldBe FoodErrorCode.BLANK_SEARCH_KEYWORD
                foodRepository.searchCallCount shouldBe 0
            }
        }

        `when`("검색어가 null 이면") {
            then("BLANK_SEARCH_KEYWORD 예외를 던지고 검색 포트를 호출하지 않는다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                val exception = shouldThrow<FoodException> {
                    useCase(foodRepository).search(SearchMenusInput(keyword = null, cursor = null, lang = null))
                }

                exception.errorCode shouldBe FoodErrorCode.BLANK_SEARCH_KEYWORD
                foodRepository.searchCallCount shouldBe 0
            }
        }
    }

    given("검색어 메뉴 조회 유스케이스 — 검색어 정규화·포트 위임") {
        `when`("앞뒤 공백이 있는 검색어로 조회하면") {
            then("trim 된 검색어로 검색 포트에 위임한다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                useCase(foodRepository).search(SearchMenusInput(keyword = " 김치 ", cursor = null, lang = null))

                foodRepository.requestedKeyword shouldBe "김치"
            }
        }

        `when`("lang 을 지정하지 않고 조회하면") {
            then("검색 포트에 ko 로 폴백한 언어를 전달한다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                useCase(foodRepository).search(SearchMenusInput(keyword = "김치", cursor = null, lang = null))

                foodRepository.requestedLang shouldBe LanguageCode.KO
            }
        }

        `when`("lang=en 으로 조회하면") {
            then("검색 포트에 en 을 전달한다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                useCase(foodRepository).search(SearchMenusInput(keyword = "bibim", cursor = null, lang = "en"))

                foodRepository.requestedLang shouldBe LanguageCode.EN
            }
        }

        `when`("cursor 를 지정해 조회하면") {
            then("검색 포트를 해당 cursor·페이지 크기+1(21)로 호출한다") {
                val foodRepository = SearchFakeFoodRepository(emptyList())

                useCase(foodRepository).search(SearchMenusInput(keyword = "김치", cursor = 100L, lang = null))

                foodRepository.requestedCursor shouldBe 100L
                foodRepository.requestedSize shouldBe 21
            }
        }
    }

    given("검색어 메뉴 조회 유스케이스 — 최신순 keyset 페이지네이션") {
        `when`("포트가 페이지 크기+1(21)개를 반환하면") {
            then("hasNext=true·items 20개이며 nextCursor 는 마지막 항목 foodId 다") {
                val result = useCase(SearchFakeFoodRepository(descendingFoods(21)))
                    .search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.hasNext shouldBe true
                result.items.size shouldBe 20
                result.nextCursor shouldBe result.items.last().foodId
            }

            then("21번째 항목은 절단돼 items 에 담기지 않는다") {
                val result = useCase(SearchFakeFoodRepository(descendingFoods(21)))
                    .search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.map { it.foodId } shouldBe (21 downTo 2).map { it.toLong() }
                result.items.map { it.foodId } shouldNotContain 1L
            }
        }

        `when`("포트가 페이지 크기보다 적은 5건을 반환하면") {
            then("마지막 페이지로 보고 hasNext=false·nextCursor=null 이며 5개를 담는다") {
                val result = useCase(SearchFakeFoodRepository(descendingFoods(5)))
                    .search(SearchMenusInput(keyword = "메뉴", cursor = 50L, lang = null))

                result.items.size shouldBe 5
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }

        `when`("포트가 페이지 크기와 정확히 같은 20건을 반환하면") {
            then("마지막 페이지로 보고 hasNext=false·nextCursor=null 이다") {
                val result = useCase(SearchFakeFoodRepository(descendingFoods(20)))
                    .search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.size shouldBe 20
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }

        `when`("포트가 0건을 반환하면 (매칭 없음)") {
            then("items 는 빈 리스트이고 hasNext=false·nextCursor=null 이다 (오류 아님)") {
                val result = useCase(SearchFakeFoodRepository(emptyList()))
                    .search(SearchMenusInput(keyword = "없는메뉴", cursor = null, lang = null))

                result.items shouldBe emptyList()
                result.hasNext shouldBe false
                result.nextCursor shouldBe null
            }
        }
    }

    given("검색어 메뉴 조회 — 항목 종합 위험도 (사용자 회피 ∩ 성분 포함확률 최악값, US3)") {
        `when`("회피 성분을 포함한 food(SOY100·MILK30)와 미포함 food(WHEAT100)가 섞여 있으면") {
            then("포함 food 는 포함확률 규칙대로(DANGER·CAUTION), 미포함 food 는 SAFE 로 판정한다") {
                val result = searchUseCase(
                    foods = listOf(
                        richFood(10, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100))),
                        richFood(11, substances = listOf(avoidedRef(AvoidanceSubstanceCode.MILK, 30))),
                        richFood(12, substances = listOf(avoidedRef(AvoidanceSubstanceCode.WHEAT, 100))),
                    ),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.MILK),
                    catalog = listOf(
                        catalogSubstance(AvoidanceSubstanceCode.SOY),
                        catalogSubstance(AvoidanceSubstanceCode.MILK),
                        catalogSubstance(AvoidanceSubstanceCode.WHEAT),
                    ),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                val statusById = result.items.associate { it.foodId to it.overallRiskStatus }
                statusById[10L] shouldBe RiskLevel.DANGER
                statusById[11L] shouldBe RiskLevel.CAUTION
                statusById[12L] shouldBe RiskLevel.SAFE
            }
        }

        `when`("회피 성분과 겹치는 성분이 하나도 없으면") {
            then("overallRiskStatus 는 SAFE 다") {
                val result = searchUseCase(
                    foods = listOf(richFood(13, substances = listOf(avoidedRef(AvoidanceSubstanceCode.WHEAT, 100)))),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY),
                    catalog = listOf(catalogSubstance(AvoidanceSubstanceCode.WHEAT)),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.single().overallRiskStatus shouldBe RiskLevel.SAFE
            }
        }

        `when`("사용자가 회피하는 SOY 를 food 가 100% 포함하지만 카탈로그(findByCodes)에서 SOY 가 빠지면") {
            then("소프트삭제된 성분은 판정 대상에서 제외돼 overallRiskStatus 는 SAFE 다") {
                val result = searchUseCase(
                    foods = listOf(richFood(20, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100)))),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY),
                    catalog = emptyList(),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.single().overallRiskStatus shouldBe RiskLevel.SAFE
            }
        }

        `when`("동일 food·회피 조건에서 카탈로그에 SOY 가 존재하면") {
            then("대비적으로 교집합이 걸려 overallRiskStatus 는 DANGER 로 반영된다") {
                val result = searchUseCase(
                    foods = listOf(richFood(20, substances = listOf(avoidedRef(AvoidanceSubstanceCode.SOY, 100)))),
                    avoidedCodes = setOf(AvoidanceSubstanceCode.SOY),
                    catalog = listOf(catalogSubstance(AvoidanceSubstanceCode.SOY)),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.single().overallRiskStatus shouldBe RiskLevel.DANGER
            }
        }
    }

    given("검색어 메뉴 조회 — 표시명 지역화·koreanName 규약 (US3, 목록·상세와 동일)") {
        `when`("lang=en 이고 en 번역이 있으면") {
            then("표시명은 영어이고 koreanName 에 한국어 원문을 담는다") {
                val result = searchUseCase(
                    foods = listOf(richFood(30, nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"))),
                    avoidedCodes = emptySet(),
                    catalog = emptyList(),
                ).search(SearchMenusInput(keyword = "kimchi", cursor = null, lang = "en"))

                result.items.single().name shouldBe "Kimchi Stew"
                result.items.single().koreanName shouldBe "메뉴-30"
            }
        }

        `when`("lang=en 이지만 해당 food 에 en 번역이 없으면") {
            then("표시명을 한국어로 폴백하고 koreanName 은 null 이다(중복 미노출)") {
                val result = searchUseCase(
                    foods = listOf(richFood(31)),
                    avoidedCodes = emptySet(),
                    catalog = emptyList(),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = "en"))

                result.items.single().name shouldBe "메뉴-31"
                result.items.single().koreanName shouldBe null
            }
        }

        `when`("lang 미지정이면(표시명이 곧 한국어)") {
            then("koreanName 은 null 이다(중복 미노출)") {
                val result = searchUseCase(
                    foods = listOf(richFood(30, nameTranslations = mapOf(LanguageCode.EN to "Kimchi Stew"))),
                    avoidedCodes = emptySet(),
                    catalog = emptyList(),
                ).search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.single().name shouldBe "메뉴-30"
                result.items.single().koreanName shouldBe null
            }
        }
    }

    given("검색어 메뉴 조회 — 항목 foodId 정합 (상세 조회 식별자, FR-009)") {
        `when`("food.id 를 가진 항목을 조회하면") {
            then("응답 항목 foodId 가 food.id 와 순서대로 일치한다") {
                val result = searchUseCase(listOf(richFood(42), richFood(7)), emptySet(), emptyList())
                    .search(SearchMenusInput(keyword = "메뉴", cursor = null, lang = null))

                result.items.map { it.foodId } shouldBe listOf(42L, 7L)
            }
        }
    }
})

private class SearchFakeFoodRepository(
    private val page: List<Food>,
) : FoodRepository {
    var searchCallCount = 0
        private set
    var requestedKeyword: String? = null
        private set
    var requestedLang: LanguageCode? = null
        private set
    var requestedCursor: Long? = null
        private set
    var requestedSize: Int? = null
        private set

    override fun findById(id: Long): Food? = null

    override fun findMenuPage(cursor: Long?, size: Int): List<Food> = emptyList()

    override fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        searchCallCount++
        requestedKeyword = keyword
        requestedLang = lang
        requestedCursor = cursor
        requestedSize = size
        return page.take(size)
    }
}

private class SearchFakeAvoidedSubstanceProvider(
    private val codes: Set<AvoidanceSubstanceCode>,
) : AvoidedSubstanceProvider {
    override fun avoidedCodes() = codes
}

private class SearchFakeAvoidanceSubstanceRepository(
    private val substances: List<AvoidanceSubstance>,
) : AvoidanceSubstanceRepository {
    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> =
        substances.filter { it.code in codes }
}
