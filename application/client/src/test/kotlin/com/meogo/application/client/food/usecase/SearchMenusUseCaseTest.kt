package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.SearchMenusInput
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.Food
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodErrorCode
import com.meogo.core.food.FoodException
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
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
