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

                    val page = adapter.findFoodPage(null, 20)

                    page.map { it.id } shouldBe ids.sortedDescending().take(20)
                }
            }

            `when`("커서를 지정해 다음 페이지를 조회하면") {
                then("id 가 커서보다 작은 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val ids = (1..5).map { saveFood("커서경계-메뉴$it") }
                    val cursor = ids.sorted()[2]

                    val page = adapter.findFoodPage(cursor, 20)

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

                    val page = adapter.findFoodPage(null, 20)

                    page.map { it.id } shouldBe listOf(last, first)
                }
            }
        }

        given("Food 저장소 어댑터 — 검색어 부분 일치(한국어명)") {
            `when`("한국어명 조각으로 검색하면") {
                then("한국어명에 그 조각을 포함하는 메뉴만 반환한다") {
                    clearFoods()
                    val stew = saveFood("김치찌개")
                    val friedRice = saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    val page = adapter.searchFoodPage("김치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldContainExactlyInAnyOrder listOf(stew, friedRice)
                }
            }

            `when`("어떤 이름에도 없는 검색어로 검색하면") {
                then("빈 목록을 반환한다") {
                    clearFoods()
                    saveFood("김치찌개")

                    adapter.searchFoodPage("파스타", LanguageCode.KO, null, 20) shouldBe emptyList()
                }
            }

            `when`("검색 결과에 커서를 지정하면") {
                then("id 가 커서보다 작은 매칭 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val ids = (1..3).map { saveFood("커서검색-김치$it") }
                    val cursor = ids.sorted()[2]

                    val page = adapter.searchFoodPage("김치", LanguageCode.KO, cursor, 20)

                    page.map { it.id } shouldBe ids.filter { it < cursor }.sortedDescending()
                }
            }
        }

        given("Food 저장소 어댑터 — 검색 keyset 커서 경계 (US2)") {
            `when`("커서 안쪽에 매칭되지 않는 메뉴가 섞여 있으면") {
                then("id 가 커서보다 작은 매칭 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val first = saveFood("커서혼합-김치찌개")
                    saveFood("커서혼합-된장찌개")
                    val second = saveFood("커서혼합-김치볶음밥")
                    saveFood("커서혼합-순두부찌개")
                    val cursor = saveFood("커서혼합-김치만두")

                    val page = adapter.searchFoodPage("김치", LanguageCode.KO, cursor, 20)

                    page.map { it.id } shouldBe listOf(second, first)
                }
            }

            `when`("커서가 매칭 항목의 최소 id 이하이면") {
                then("빈 목록을 반환한다") {
                    clearFoods()
                    val smallest = saveFood("커서소진-김치찌개")
                    saveFood("커서소진-김치볶음밥")

                    adapter.searchFoodPage("김치", LanguageCode.KO, smallest, 20) shouldBe emptyList()
                }
            }
        }

        given("Food 저장소 어댑터 — 검색어 부분 일치(요청 언어 번역명)") {
            `when`("영어 번역명 조각을 소문자로 검색하면 (lang=en)") {
                then("대소문자를 구분하지 않고 번역명 매칭 메뉴를 반환한다") {
                    clearFoods()
                    val bibimbap = saveFood("비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))
                    saveFood("된장찌개", nameTranslations = mapOf("en" to "Doenjang Stew"))

                    val page = adapter.searchFoodPage("bibim", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(bibimbap)
                }
            }

            `when`("일본어 번역명에만 검색어가 있는 메뉴를 lang=en 으로 검색하면") {
                then("요청 언어가 아니므로 결과에 포함되지 않는다") {
                    clearFoods()
                    saveFood("냉면", nameTranslations = mapOf("ja" to "ネンミョン", "en" to "Cold Noodles"))

                    adapter.searchFoodPage("ネンミョン", LanguageCode.EN, null, 20) shouldBe emptyList()
                }
            }

            `when`("번역명에만 있는 검색어를 lang=ko 로 검색하면") {
                then("ko 는 한국어명만 매칭하므로 결과에 포함되지 않는다") {
                    clearFoods()
                    saveFood("비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))

                    adapter.searchFoodPage("Bibimbap", LanguageCode.KO, null, 20) shouldBe emptyList()
                }
            }

            `when`("한국어명과 요청 언어 번역명 양쪽에 검색어가 있으면") {
                then("결과에 한 번만 담긴다") {
                    clearFoods()
                    val id = saveFood("Bibim비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))

                    val page = adapter.searchFoodPage("bibim", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }
        }

        given("Food 저장소 어댑터 — 검색어의 패턴 특수문자는 리터럴로 매칭한다 (FR-003a)") {
            fun seedWildcardFoods(): Map<String, Long> {
                clearFoods()
                saveFood("김치찌개")
                saveFood("된장찌개")
                saveFood("비빔밥")
                saveFood("50000원 세트")
                return mapOf(
                    "percent" to saveFood("할인 50% 세트"),
                    "underscore" to saveFood("김치_특"),
                    "backslash" to saveFood("백슬래시\\테스트"),
                )
            }

            `when`("검색어가 % 하나이면") {
                then("전체 메뉴가 아니라 이름에 % 를 포함하는 메뉴만 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = adapter.searchFoodPage("%", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("percent"))
                }
            }

            `when`("검색어가 _ 하나이면") {
                then("임의 1문자 와일드카드가 아니라 이름에 _ 를 포함하는 메뉴만 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = adapter.searchFoodPage("_", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("underscore"))
                }
            }

            `when`("검색어에 % 가 리터럴로 섞여 있으면 (50%)") {
                then("그 조각을 이름에 포함하는 메뉴를 부분 일치로 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = adapter.searchFoodPage("50%", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("percent"))
                }
            }

            `when`("검색어 가운데에 _ 가 섞여 있으면 (김_치)") {
                then("임의 1문자로 해석하지 않아 김밥치즈 는 매칭되지 않는다") {
                    clearFoods()
                    saveFood("김치찌개")
                    saveFood("김밥치즈")
                    val underscore = saveFood("김_치")

                    val page = adapter.searchFoodPage("김_치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(underscore)
                }
            }

            `when`("검색어가 이스케이프 문자 자체(백슬래시)이면") {
                then("이스케이프 문자도 리터럴로 취급해 백슬래시를 포함하는 메뉴만 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = adapter.searchFoodPage("\\", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("backslash"))
                }
            }

            `when`("요청 언어 번역명 경로에서 검색어가 % 하나이면") {
                then("전체가 아니라 번역명에 % 를 포함하는 메뉴만 반환한다") {
                    clearFoods()
                    saveFood("일반세트", nameTranslations = mapOf("en" to "Normal Set"))
                    val sale = saveFood("세일세트", nameTranslations = mapOf("en" to "50% Off Set"))

                    val page = adapter.searchFoodPage("%", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(sale)
                }
            }
        }

        given("Food 저장소 어댑터 — 하이픈이 든 언어 코드(zh-Hans·zh-Hant) 번역명 매칭") {
            fun seedChineseFood(): Long {
                clearFoods()
                saveFood("된장찌개")
                return saveFood(
                    "중국어번역-메뉴",
                    nameTranslations = mapOf("zh-Hans" to "拌饭简体", "zh-Hant" to "拌飯繁體"),
                )
            }

            `when`("간체 번역명 조각을 lang=zh-Hans 로 검색하면") {
                then("JSON 경로가 인용돼 간체 번역명으로 매칭한다") {
                    val id = seedChineseFood()

                    val page = adapter.searchFoodPage("简体", LanguageCode.ZH_HANS, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }

            `when`("번체 번역명 조각을 lang=zh-Hant 로 검색하면") {
                then("JSON 경로가 인용돼 번체 번역명으로 매칭한다") {
                    val id = seedChineseFood()

                    val page = adapter.searchFoodPage("繁體", LanguageCode.ZH_HANT, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }

            `when`("간체 번역명 조각을 lang=zh-Hant 로 교차 검색하면") {
                then("하이픈 코드에서도 언어 분리가 성립해 매칭되지 않는다") {
                    seedChineseFood()

                    adapter.searchFoodPage("简体", LanguageCode.ZH_HANT, null, 20) shouldBe emptyList()
                }
            }
        }

        given("Food 저장소 어댑터 — 한국어명 경로의 대소문자 비구분 (FR-003)") {
            `when`("한국어명에 든 라틴 대문자를 소문자 검색어로 찾으면 (lang=KO)") {
                then("대소문자를 구분하지 않고 매칭한다") {
                    clearFoods()
                    val bbq = saveFood("BBQ 치킨")
                    saveFood("김치찌개")

                    val page = adapter.searchFoodPage("bbq", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(bbq)
                }
            }

            `when`("한국어명에 든 라틴 소문자를 대문자 검색어로 찾으면 (lang=KO)") {
                then("대소문자를 구분하지 않고 매칭한다") {
                    clearFoods()
                    val latte = saveFood("Latte 라떼")
                    saveFood("김치찌개")

                    val page = adapter.searchFoodPage("LATTE", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(latte)
                }
            }
        }

        given("Food 저장소 어댑터 — 검색은 소프트 삭제된 메뉴를 제외한다") {
            `when`("검색어가 매칭되는 메뉴가 소프트 삭제되어 있으면") {
                then("네이티브 검색 쿼리도 ACTIVE 만 반환한다") {
                    clearFoods()
                    val alive = saveFood("소프트삭제검색-김치찌개")
                    val deleted = saveFood("소프트삭제검색-김치볶음밥")
                    val deletedEntity = foodJpaRepository.findById(deleted).get()
                    deletedEntity.delete()
                    foodJpaRepository.save(deletedEntity)

                    val page = adapter.searchFoodPage("김치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(alive)
                }
            }
        }
    }
}
