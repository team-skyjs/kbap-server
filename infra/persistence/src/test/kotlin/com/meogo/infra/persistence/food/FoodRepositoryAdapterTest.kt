package com.meogo.infra.persistence.food
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import javax.sql.DataSource

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@Import(MySqlContainerConfig::class)
class FoodRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodRepositoryAdapter

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clearFoods() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM food_avoidance_substance")
                    statement.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(
            koreanName: String,
            imageRef: String? = null,
            substances: List<Pair<String, Int>> = emptyList(),
            description: String = "구수한 $koreanName",
            spiciness: Int = 0,
            nameTranslations: Map<String, String> = emptyMap(),
            descriptionTranslations: Map<String, String> = emptyMap(),
        ): Long {
            val food = FoodJpaEntity(
                koreanName = koreanName,
                imageRef = imageRef,
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

        given("Food 저장소 어댑터 — 포함 기피 성분 복원") {
            `when`("foodId 로 조회하면") {
                then("음식과 포함 기피 성분(코드·확률)을 복원한다(정렬은 서비스단 책임)") {
                    val id = saveFood(
                        "구조복원-된장찌개",
                        imageRef = "doenjang.png",
                        substances = listOf(
                            "CLAM" to 50,
                            "SOYBEAN" to 100,
                            "TOFU" to 90,
                        ),
                    )

                    val loaded = adapter.findById(id)
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

            `when`("미존재 id 로 조회하면") {
                then("null 을 반환한다") {
                    adapter.findById(99999L) shouldBe null
                }
            }

            `when`("저장된 음식을 소프트 삭제하면") {
                then("@SQLRestriction 으로 조회에서 제외돼 null 이 반환된다") {
                    val savedId = saveFood("삭제-순두부찌개", substances = listOf("SOYBEAN" to 95))

                    val entity = foodJpaRepository.findById(savedId).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    adapter.findById(savedId).shouldBeNull()
                }
            }
        }

        given("Food 저장소 어댑터 — 음식 구성 복원") {
            `when`("foodId 로 조회하면") {
                then("설명·맵기 원문을 도메인으로 복원한다") {
                    val id = saveFood(
                        "구성복원-된장찌개",
                        description = "된장찌개는 된장을 푼 한국의 대표 찌개다.",
                        spiciness = 4,
                    )

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.content.description.korean shouldBe "된장찌개는 된장을 푼 한국의 대표 찌개다."
                    loaded.spiciness.value shouldBe 4
                }
            }
        }

        given("Food 저장소 어댑터 — 번역 JSON 칼럼 라운드트립") {
            `when`("name_translations·description_translations JSON 을 심고 조회하면") {
                then("LanguageCode 키 맵으로 복원한다") {
                    val id = saveFood(
                        "번역복원-된장찌개",
                        description = "된장찌개는 된장을 푼 찌개다.",
                        nameTranslations = mapOf("en" to "Doenjang Stew", "ja" to "テンジャンチゲ"),
                        descriptionTranslations = mapOf("en" to "A hearty stew."),
                    )

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.content.name.translations shouldContainExactly mapOf(
                        LanguageCode.EN to "Doenjang Stew",
                        LanguageCode.JA to "テンジャンチゲ",
                    )
                    loaded.content.description.translations shouldContainExactly mapOf(
                        LanguageCode.EN to "A hearty stew.",
                    )
                }
            }

            `when`("복원한 콘텐츠로 요청 언어를 해석하면") {
                then("번역이 있는 언어는 번역값, 없는 언어는 한국어 원문으로 폴백한다") {
                    val id = saveFood(
                        "폴백복원-된장찌개",
                        description = "된장찌개는 된장을 푼 찌개다.",
                        nameTranslations = mapOf("en" to "Doenjang Stew"),
                        descriptionTranslations = mapOf("en" to "A hearty stew."),
                    )

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.content.name.resolve(LanguageCode.EN) shouldBe "Doenjang Stew"
                    loaded.content.name.resolve(LanguageCode.JA) shouldBe "폴백복원-된장찌개"
                    loaded.content.description.resolve(LanguageCode.EN) shouldBe "A hearty stew."
                    loaded.content.description.resolve(LanguageCode.JA) shouldBe "된장찌개는 된장을 푼 찌개다."
                    loaded.content.name.resolve(LanguageCode.KO) shouldBe "폴백복원-된장찌개"
                    loaded.content.description.resolve(LanguageCode.KO) shouldBe "된장찌개는 된장을 푼 찌개다."
                }
            }

            `when`("JSON 에 미지의 언어 키가 섞여 있으면") {
                then("복원 시 무시되고 맵에 들어가지 않는다") {
                    val id = saveFood(
                        "미지키-된장찌개",
                        nameTranslations = mapOf("en" to "Doenjang Stew", "xx" to "Unknown"),
                        descriptionTranslations = mapOf("en" to "A hearty stew.", "zz" to "Unknown"),
                    )

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.content.name.translations shouldContainExactly mapOf(LanguageCode.EN to "Doenjang Stew")
                    loaded.content.name.translations shouldNotContainKey LanguageCode.KO
                    loaded.content.description.translations shouldContainExactly mapOf(LanguageCode.EN to "A hearty stew.")
                }
            }
        }

        given("Food 저장소 어댑터 — 포함 기피 성분 없음") {
            `when`("포함 기피 성분이 하나도 없는 음식을 저장하면") {
                then("정상 저장되고 빈 목록으로 복원된다") {
                    val id = saveFood("성분없음-흰밥", substances = emptyList())

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.avoidanceSubstances shouldBe emptyList()
                }
            }

            `when`("번역이 하나도 없는 음식을 저장하면") {
                then("빈 번역 맵으로 복원된다") {
                    val id = saveFood("번역없음-흰밥")

                    val loaded = adapter.findById(id).shouldNotBeNull()
                    loaded.content.name.translations shouldBe emptyMap()
                    loaded.content.description.translations shouldBe emptyMap()
                }
            }
        }

        given("Food 저장소 어댑터 — fetch join 상수 쿼리(N+1 없음)") {
            `when`("포함 기피 성분·번역이 여러 개인 음식을 조회하면") {
                then("성분·번역 개수와 무관하게 단일 SQL 로 로드한다") {
                    val id = saveFood(
                        "N플러스원-부대찌개",
                        substances = listOf(
                            "EGG" to 70,
                            "SOYBEAN" to 100,
                            "PORK" to 90,
                            "WHEAT" to 60,
                        ),
                        nameTranslations = mapOf("en" to "Budae Jjigae", "ja" to "プデチゲ"),
                        descriptionTranslations = mapOf("en" to "Army stew."),
                    )
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    statistics.clear()

                    val loaded = adapter.findById(id).shouldNotBeNull()

                    loaded.avoidanceSubstances.size shouldBe 4
                    loaded.content.name.translations.size shouldBe 2
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

        given("Food 저장소 어댑터 — 메뉴 목록 keyset 페이지네이션") {
            `when`("커서 없이 첫 페이지(20개)를 조회하면") {
                then("최신순(id 내림차순) 상위 20개를 반환한다") {
                    clearFoods()
                    val ids = (1..22).map { saveFood("목록정렬-메뉴$it") }

                    val page = adapter.findMenuPage(null, 20)

                    page.map { it.id } shouldBe ids.sortedDescending().take(20)
                }
            }

            `when`("커서를 지정해 다음 페이지를 조회하면") {
                then("id 가 커서보다 작은 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val ids = (1..5).map { saveFood("커서경계-메뉴$it") }
                    val cursor = ids.sorted()[2]

                    val page = adapter.findMenuPage(cursor, 20)

                    page.map { it.id } shouldBe ids.filter { it < cursor }.sortedDescending()
                }
            }

            `when`("소프트 삭제된 음식이 섞여 있으면") {
                then("ACTIVE 필터로 목록에서 제외된다") {
                    clearFoods()
                    val first = saveFood("소프트삭제-메뉴1")
                    val deleted = saveFood("소프트삭제-메뉴2")
                    val last = saveFood("소프트삭제-메뉴3")
                    val deletedEntity = foodJpaRepository.findById(deleted).get()
                    deletedEntity.delete()
                    foodJpaRepository.save(deletedEntity)

                    val page = adapter.findMenuPage(null, 20)

                    page.map { it.id } shouldBe listOf(last, first)
                }
            }
        }

        given("Food 저장소 어댑터 — 정규화 매칭 키 배치 조회") {
            `when`("여러 정규화 키로 한 번에 조회하면") {
                then("키별 음식을 담은 맵을 반환하고 없는 키는 빠진다") {
                    clearFoods()
                    val kimchi = saveFood("김치찌개")
                    val gukbap = saveFood("돼지 국밥")

                    val found = adapter.findByKoreanMatchKeys(setOf("김치찌개", "돼지국밥", "없는메뉴"))

                    found.keys shouldBe setOf("김치찌개", "돼지국밥")
                    found.getValue("김치찌개").id shouldBe kimchi
                    found.getValue("돼지국밥").id shouldBe gukbap
                }
            }

            `when`("미완성(INCOMPLETE) 음식이 키와 일치하면") {
                then("스캔 매칭 대상이므로 포함된다") {
                    clearFoods()
                    adapter.createIncomplete("우주라면")

                    val found = adapter.findByKoreanMatchKeys(setOf("우주라면"))

                    found.getValue("우주라면").isReady() shouldBe false
                }
            }

            `when`("같은 정규화 키를 가진 음식이 둘 이상이면") {
                then("가장 작은 id 의 음식을 반환한다") {
                    clearFoods()
                    val first = saveFood("국밥")
                    saveFood("국 밥")

                    adapter.findByKoreanMatchKeys(setOf("국밥")).getValue("국밥").id shouldBe first
                }
            }

            `when`("소프트 삭제된 음식만 키가 일치하면") {
                then("@SQLRestriction 으로 제외된다") {
                    clearFoods()
                    val id = saveFood("삭제된김밥")
                    val entity = foodJpaRepository.findById(id).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    adapter.findByKoreanMatchKeys(setOf("삭제된김밥")) shouldBe emptyMap()
                }
            }

            `when`("빈 키 집합으로 조회하면") {
                then("빈 맵을 반환한다(쿼리 없음)") {
                    adapter.findByKoreanMatchKeys(emptySet()) shouldBe emptyMap()
                }
            }
        }

        given("Food 저장소 어댑터 — 미완성 음식 생성") {
            `when`("스캔 miss 로 미완성 음식을 만들면") {
                then("INCOMPLETE 상태로 저장되고 id 가 부여된다") {
                    clearFoods()

                    val created = adapter.createIncomplete("마라샹궈")

                    created.id.shouldNotBeNull()
                    created.koreanName() shouldBe "마라샹궈"
                    created.isReady() shouldBe false
                }
            }

            `when`("같은 이름으로 두 번 생성하면") {
                then("중복 없이 기존 음식을 반환한다") {
                    clearFoods()
                    val first = adapter.createIncomplete("마라탕")
                    val second = adapter.createIncomplete("마라탕")

                    second.id shouldBe first.id
                    foodJpaRepository.count() shouldBe 1
                }
            }
        }

        given("Food 저장소 어댑터 — 미완성 음식 노출 차단(serving gate)") {
            `when`("미완성 음식이 섞인 채 메뉴 목록을 조회하면") {
                then("READY 음식만 반환한다") {
                    clearFoods()
                    val ready = saveFood("완성-비빔밥")
                    adapter.createIncomplete("미완성-우주라면")

                    adapter.findMenuPage(null, 20).map { it.id } shouldBe listOf(ready)
                }
            }

            `when`("미완성 음식을 id 로 상세 조회하면") {
                then("null 을 반환한다") {
                    clearFoods()
                    val incompleteId = adapter.createIncomplete("미완성-마라탕").id!!

                    adapter.findById(incompleteId) shouldBe null
                }
            }
        }
    }
}
