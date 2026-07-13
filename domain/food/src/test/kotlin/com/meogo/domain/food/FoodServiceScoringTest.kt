package com.meogo.domain.food

import com.meogo.core.lang.LanguageCode
import com.meogo.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodServiceScoringTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: FoodService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    init {
        fun saveFood(
            koreanName: String,
            substances: List<Pair<String, Int>> = emptyList(),
            description: String = "구수한 $koreanName",
            spiciness: Int = 0,
            nameTranslations: Map<String, String> = emptyMap(),
            descriptionTranslations: Map<String, String> = emptyMap(),
        ): Long {
            val food = FoodJpaEntity(
                koreanName = koreanName,
                description = description,
                spiciness = spiciness,
                nameTranslations = nameTranslations,
                descriptionTranslations = descriptionTranslations,
                foodAvoidanceSubstances = substances.map { (code, percent) ->
                    FoodAvoidanceSubstanceJpaEntity(
                        substanceCode = code,
                        inclusionPercent = percent,
                    )
                }.toMutableSet(),
            )
            return foodJpaRepository.save(food).id
        }

        given("Food 스코어링 공급 어댑터 — 청크 크기 상한") {
            `when`("active food 가 요청 size 보다 많을 때 nextChunk 를 호출하면") {
                then("요청 size 만큼만 반환한다") {
                    foodJpaRepository.deleteAll()
                    val koreanNames = listOf("상한-된장찌개", "상한-김치찌개", "상한-순두부", "상한-부대찌개", "상한-청국장")
                    koreanNames.forEach { saveFood(it) }

                    val chunk = service.nextChunk(0, 3)

                    chunk.size shouldBeLessThanOrEqual 3
                    chunk.size shouldBe 3
                    chunk.forEach { it.displayName(LanguageCode.KO) shouldBeIn koreanNames }
                }
            }
        }

        given("Food 스코어링 공급 어댑터 — 잔여가 size 보다 적은 마지막 청크") {
            `when`("active food 가 요청 size 보다 적을 때 nextChunk 를 호출하면") {
                then("남은 것 전부를 반환한다") {
                    foodJpaRepository.deleteAll()
                    saveFood("잔여-된장찌개")
                    saveFood("잔여-김치찌개")

                    val chunk = service.nextChunk(0, 10)

                    chunk.size shouldBeLessThanOrEqual 10
                    chunk.size shouldBe 2
                    chunk.map { it.displayName(LanguageCode.KO) }
                        .shouldContainExactlyInAnyOrder("잔여-된장찌개", "잔여-김치찌개")
                }
            }
        }

        given("Food 스코어링 공급 어댑터 — 빈 대기열") {
            `when`("active food 가 하나도 없을 때 nextChunk 를 호출하면") {
                then("빈 목록을 반환한다") {
                    foodJpaRepository.deleteAll()

                    service.nextChunk(0, 10).shouldBeEmpty()
                }
            }
        }

        given("Food 스코어링 공급 어댑터 — 소프트 삭제 제외") {
            `when`("active 음식과 소프트 삭제된 음식이 함께 있을 때 nextChunk 를 호출하면") {
                then("active 음식만 반환하고 삭제된 음식은 제외한다") {
                    foodJpaRepository.deleteAll()
                    saveFood("삭제제외-생존-된장찌개")
                    val deletedId = saveFood("삭제제외-삭제-김치찌개")
                    val deletedEntity = foodJpaRepository.findById(deletedId).get()
                    deletedEntity.delete()
                    foodJpaRepository.save(deletedEntity)

                    val chunk = service.nextChunk(0, 10)

                    chunk.map { it.displayName(LanguageCode.KO) }
                        .shouldContainExactlyInAnyOrder("삭제제외-생존-된장찌개")
                }
            }
        }

        given("Food 스코어링 공급 어댑터 — 도메인 복원 충실성") {
            `when`("포함 기피 성분과 번역을 가진 음식을 nextChunk 로 가져오면") {
                then("한국어명·포함 성분이 도메인 Food 로 복원된다") {
                    foodJpaRepository.deleteAll()
                    saveFood(
                        "복원-된장찌개",
                        substances = listOf("SOYBEAN" to 100, "CLAM" to 50),
                        nameTranslations = mapOf("en" to "Doenjang Stew"),
                    )

                    val chunk = service.nextChunk(0, 10)

                    val food = chunk.firstOrNull { it.displayName(LanguageCode.KO) == "복원-된장찌개" }
                    food.shouldNotBeNull()
                    food.displayName(LanguageCode.KO) shouldBe "복원-된장찌개"
                    food.avoidanceSubstances.map { it.substanceCode.value }
                        .shouldContainExactlyInAnyOrder("SOYBEAN", "CLAM")
                }
            }
        }

        given("Food 스코어링 공급 어댑터 — 페이지 커서로 전체 대기열 종단 소진") {
            `when`("active food 23건을 size 10 으로 페이지 0·1·2·3 순차 조회하면") {
                then("각 페이지가 10·10·3·빈 으로 나뉘고 세 페이지 union 이 저장한 23건 전부와 정확히 일치한다(중복·누락 없음)") {
                    foodJpaRepository.deleteAll()
                    val savedIds = (1..23).map { saveFood("종단소진-음식$it") }.toSet()

                    val page0 = service.nextChunk(0, 10)
                    val page1 = service.nextChunk(1, 10)
                    val page2 = service.nextChunk(2, 10)
                    val page3 = service.nextChunk(3, 10)

                    page0 shouldHaveSize 10
                    page1 shouldHaveSize 10
                    page2 shouldHaveSize 3
                    page3.shouldBeEmpty()

                    val collectedIds = (page0 + page1 + page2).mapNotNull { it.id }
                    collectedIds shouldHaveSize 23
                    collectedIds.toSet() shouldBe savedIds
                }
            }
        }
    }
}
