package com.meogo.infra.persistence.food

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class FoodRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodRepositoryAdapter

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodNameTranslationJpaRepository: FoodNameTranslationJpaRepository

    @Autowired
    private lateinit var foodDescriptionTranslationJpaRepository: FoodDescriptionTranslationJpaRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    init {
        fun saveFood(
            koreanName: String,
            imageRef: String? = null,
            substances: List<Pair<String, Int>> = emptyList(),
            description: String = "구수한 $koreanName",
            spiciness: Int = 0,
        ): Long {
            val food = FoodJpaEntity(
                koreanName = koreanName,
                imageRef = imageRef,
                description = description,
                spiciness = spiciness,
                foodAvoidanceSubstances = substances.map { (code, percent) ->
                    FoodAvoidanceSubstanceJpaEntity(
                        substanceCode = code,
                        inclusionPercent = percent,
                    )
                }.toMutableSet(),
            )
            return foodJpaRepository.save(food).id
        }

        given("Food 저장소 어댑터 — 포함 기피 성분 복원") {
            `when`("한국어 메뉴명으로 조회하면") {
                then("음식과 포함 기피 성분(코드·확률)을 복원한다(정렬은 서비스단 책임)") {
                    saveFood(
                        "구조복원-된장찌개",
                        imageRef = "doenjang.png",
                        substances = listOf(
                            "CLAM" to 50,
                            "SOYBEAN" to 100,
                            "TOFU" to 90,
                        ),
                    )

                    val loaded = adapter.findByKoreanName("구조복원-된장찌개")
                    loaded.shouldNotBeNull()
                    loaded.imageRef shouldBe "doenjang.png"
                    loaded.avoidanceSubstances.map { it.substanceCode.value }
                        .shouldContainExactlyInAnyOrder("CLAM", "SOYBEAN", "TOFU")
                    loaded.avoidanceSubstances.map { it.inclusionProbability }
                        .shouldContainExactlyInAnyOrder(50, 100, 90)
                    loaded.avoidanceSubstances.first { it.substanceCode.value == "SOYBEAN" }
                        .inclusionProbability shouldBe 100
                }
            }

            `when`("앞뒤 공백이 있는 메뉴명으로 조회하면") {
                then("trim 후 매칭한다") {
                    saveFood("trim-김치찌개", substances = listOf("EGG" to 80))

                    adapter.findByKoreanName("  trim-김치찌개  ").shouldNotBeNull()
                }
            }

            `when`("수록되지 않은 메뉴명으로 조회하면") {
                then("null 을 반환한다") {
                    adapter.findByKoreanName("존재하지않는메뉴") shouldBe null
                }
            }

            `when`("저장된 음식을 소프트 삭제하면") {
                then("@SQLRestriction 으로 조회에서 제외돼 null 이 반환된다") {
                    val savedId = saveFood("삭제-순두부찌개", substances = listOf("SOYBEAN" to 95))

                    val entity = foodJpaRepository.findById(savedId).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    adapter.findByKoreanName("삭제-순두부찌개").shouldBeNull()
                }
            }
        }

        given("Food 저장소 어댑터 — 음식 구성 복원") {
            `when`("한국어 메뉴명으로 조회하면") {
                then("설명·맵기 원문을 도메인으로 복원한다") {
                    saveFood(
                        "구성복원-된장찌개",
                        description = "된장찌개는 된장을 푼 한국의 대표 찌개다.",
                        spiciness = 4,
                    )

                    val loaded = adapter.findByKoreanName("구성복원-된장찌개").shouldNotBeNull()
                    loaded.content.description shouldBe "된장찌개는 된장을 푼 한국의 대표 찌개다."
                    loaded.spiciness.value shouldBe 4
                }
            }
        }

        given("Food 저장소 어댑터 — 포함 기피 성분 없음") {
            `when`("포함 기피 성분이 하나도 없는 음식을 저장하면") {
                then("정상 저장되고 빈 목록으로 복원된다") {
                    saveFood("성분없음-흰밥", substances = emptyList())

                    val loaded = adapter.findByKoreanName("성분없음-흰밥").shouldNotBeNull()
                    loaded.avoidanceSubstances shouldBe emptyList()
                }
            }
        }

        given("Food 저장소 어댑터 — fetch join 상수 쿼리(N+1 없음)") {
            `when`("포함 기피 성분이 여러 개인 음식을 조회하면") {
                then("성분 개수와 무관하게 단일 SQL 로 로드한다") {
                    saveFood(
                        "N플러스원-부대찌개",
                        substances = listOf(
                            "EGG" to 70,
                            "SOYBEAN" to 100,
                            "PORK" to 90,
                            "WHEAT" to 60,
                        ),
                    )
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    statistics.clear()

                    val loaded = adapter.findByKoreanName("N플러스원-부대찌개").shouldNotBeNull()

                    loaded.avoidanceSubstances.size shouldBe 4
                    statistics.prepareStatementCount shouldBe 1
                }
            }
        }

        given("Food 저장소 어댑터 — (food_id, substance_code) 조합 유일") {
            `when`("같은 음식에 같은 기피 성분 코드를 두 번 등록하면") {
                then("unique 제약 위반으로 저장이 거부된다") {
                    val food = FoodJpaEntity(
                        koreanName = "중복성분-된장찌개",
                        description = "중복성분 설명",
                        spiciness = 0,
                        foodAvoidanceSubstances = mutableSetOf(
                            FoodAvoidanceSubstanceJpaEntity(substanceCode = "SOYBEAN", inclusionPercent = 100),
                            FoodAvoidanceSubstanceJpaEntity(substanceCode = "SOYBEAN", inclusionPercent = 80),
                        ),
                    )

                    shouldThrow<DataIntegrityViolationException> {
                        foodJpaRepository.saveAndFlush(food)
                    }
                }
            }
        }

        given("음식명 번역 조회 — 요청 언어만") {
            `when`("요청 언어 번역이 있으면") {
                then("해당 언어 번역을 반환한다") {
                    val foodId = saveFood("번역있음", substances = listOf("EGG" to 90))
                    foodNameTranslationJpaRepository.save(
                        FoodNameTranslationJpaEntity(foodId = foodId, langCode = "en", name = "Doenjang Stew"),
                    )

                    adapter.findFoodNameTranslation(foodId, LanguageCode.EN) shouldBe "Doenjang Stew"
                }
            }

            `when`("요청 언어 번역이 없으면") {
                then("null 을 반환한다(application 이 ko 로 폴백)") {
                    val foodId = saveFood("번역없음", substances = listOf("EGG" to 80))

                    adapter.findFoodNameTranslation(foodId, LanguageCode.JA).shouldBeNull()
                }
            }
        }

        given("음식 설명 번역 조회 — 요청 언어 단일 설명") {
            `when`("요청 언어로 설명 번역이 있으면") {
                then("해당 언어 설명을 반환한다") {
                    val foodId = saveFood("설명번역-있음")
                    foodDescriptionTranslationJpaRepository.save(
                        FoodDescriptionTranslationJpaEntity(foodId = foodId, langCode = "en", content = "A hearty stew."),
                    )

                    adapter.findFoodDescriptionTranslation(foodId, LanguageCode.EN) shouldBe "A hearty stew."
                }
            }

            `when`("요청 언어로 설명 번역이 없으면") {
                then("null 을 반환한다(application 이 ko 로 폴백)") {
                    val foodId = saveFood("설명번역-없음")

                    adapter.findFoodDescriptionTranslation(foodId, LanguageCode.JA).shouldBeNull()
                }
            }

            `when`("lang=ko 이면") {
                then("번역 테이블을 조회하지 않고 null 을 반환한다") {
                    val foodId = saveFood("설명번역-ko")
                    foodDescriptionTranslationJpaRepository.save(
                        FoodDescriptionTranslationJpaEntity(foodId = foodId, langCode = "en", content = "A hearty stew."),
                    )

                    adapter.findFoodDescriptionTranslation(foodId, LanguageCode.KO).shouldBeNull()
                }
            }
        }
    }
}
