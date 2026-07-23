package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodAvoidanceItem
import com.kbap.domain.food.model.FoodContentStatus
import com.kbap.domain.food.dto.SeedIncompleteResult
import com.kbap.core.lang.LanguageCode
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest(
    classes = [FoodServiceTestApp::class],
    properties = ["spring.jpa.properties.hibernate.generate_statistics=true"],
)
@Import(MySqlContainerConfig::class)
class FoodServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: FoodService

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
            val food = Food(
                koreanName = koreanName,
                imageRef = imageRef,
                description = description,
                spiciness = spiciness,
                nameTranslations = nameTranslations,
                descriptionTranslations = descriptionTranslations,
                avoidanceSubstances = substances.map { (code, percent) ->
                    FoodAvoidanceItem(code = code, inclusionPercent = percent)
                },
            )
            return foodJpaRepository.save(food).id
        }

        beforeContainer { clearFoods() }

        given("Food 조회 — 포함 기피 성분 복원") {
            `when`("foodId 로 조회하면") {
                then("음식과 포함 기피 성분(코드·확률)을 JSON 컬럼에서 복원한다") {
                    val id = saveFood(
                        "구조복원-된장찌개",
                        imageRef = "doenjang.png",
                        substances = listOf(
                            "CLAM" to 50,
                            "SOY" to 100,
                            "MILK" to 90,
                        ),
                    )

                    val loaded = service.getReadyFood(id)
                    loaded.imageRef shouldBe "doenjang.png"
                    loaded.avoidanceSubstances.orEmpty().map { it.code }
                        .shouldContainExactlyInAnyOrder("CLAM", "SOY", "MILK")
                    loaded.avoidanceSubstances.orEmpty().map { it.inclusionPercent }
                        .shouldContainExactlyInAnyOrder(50, 100, 90)
                    loaded.avoidanceSubstances.orEmpty().first { it.code == "SOY" }
                        .inclusionPercent shouldBe 100
                }
            }

            `when`("저장 순서가 확률 내림차순이 아니게 심겨 있으면") {
                then("avoidanceSubstancesByProbability 가 확률 내림차순으로 정렬해 복원한다") {
                    val id = saveFood(
                        "정렬복원-부대찌개",
                        substances = listOf(
                            "CLAM" to 50,
                            "SOY" to 100,
                            "WHEAT" to 80,
                        ),
                    )

                    val ordered = service.getReadyFood(id).avoidanceSubstancesByProbability()
                    ordered.map { it.inclusionPercent } shouldBe listOf(100, 80, 50)
                    ordered.map { it.code } shouldBe listOf("SOY", "WHEAT", "CLAM")
                }
            }

            `when`("미존재 id 로 조회하면") {
                then("FOOD_NOT_FOUND 예외를 던진다") {
                    shouldThrow<BusinessException> {
                        service.getReadyFood(99999L)
                    }.errorCode shouldBe ErrorCode.FOOD_NOT_FOUND
                }
            }

            `when`("저장된 음식을 소프트 삭제하면") {
                then("@SQLRestriction 으로 조회에서 제외돼 FOOD_NOT_FOUND 예외를 던진다") {
                    val savedId = saveFood("삭제-순두부찌개", substances = listOf("SOY" to 95))

                    val entity = foodJpaRepository.findById(savedId).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    shouldThrow<BusinessException> {
                        service.getReadyFood(savedId)
                    }.errorCode shouldBe ErrorCode.FOOD_NOT_FOUND
                }
            }
        }

        given("Food 조회 — 음식 구성 복원") {
            `when`("foodId 로 조회하면") {
                then("설명·맵기 원문을 복원한다") {
                    val id = saveFood(
                        "구성복원-된장찌개",
                        description = "된장찌개는 된장을 푼 한국의 대표 찌개다.",
                        spiciness = 4,
                    )

                    val loaded = service.getReadyFood(id)
                    loaded.description shouldBe "된장찌개는 된장을 푼 한국의 대표 찌개다."
                    loaded.spiciness shouldBe 4
                }
            }
        }

        given("Food 조회 — 번역 JSON 칼럼 라운드트립") {
            `when`("name_translations·description_translations JSON 을 심고 조회하면") {
                then("번역 맵 그대로 복원한다") {
                    val id = saveFood(
                        "번역복원-된장찌개",
                        description = "된장찌개는 된장을 푼 찌개다.",
                        nameTranslations = mapOf("en" to "Doenjang Stew", "ja" to "テンジャンチゲ"),
                        descriptionTranslations = mapOf("en" to "A hearty stew."),
                    )

                    val loaded = service.getReadyFood(id)
                    loaded.nameTranslations shouldContainExactly mapOf(
                        "en" to "Doenjang Stew",
                        "ja" to "テンジャンチゲ",
                    )
                    loaded.descriptionTranslations shouldContainExactly mapOf(
                        "en" to "A hearty stew.",
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

                    val loaded = service.getReadyFood(id)
                    loaded.displayName(LanguageCode.EN) shouldBe "Doenjang Stew"
                    loaded.displayName(LanguageCode.JA) shouldBe "폴백복원-된장찌개"
                    loaded.description(LanguageCode.EN) shouldBe "A hearty stew."
                    loaded.description(LanguageCode.JA) shouldBe "된장찌개는 된장을 푼 찌개다."
                    loaded.displayName(LanguageCode.KO) shouldBe "폴백복원-된장찌개"
                    loaded.description(LanguageCode.KO) shouldBe "된장찌개는 된장을 푼 찌개다."
                }
            }

            `when`("JSON 에 미지의 언어 키가 섞여 있으면") {
                then("언어 해석 시 무시되고 한국어 원문으로 폴백한다") {
                    val id = saveFood(
                        "미지키-된장찌개",
                        nameTranslations = mapOf("en" to "Doenjang Stew", "xx" to "Unknown"),
                        descriptionTranslations = mapOf("en" to "A hearty stew.", "zz" to "Unknown"),
                    )

                    val loaded = service.getReadyFood(id)
                    loaded.displayName(LanguageCode.EN) shouldBe "Doenjang Stew"
                    loaded.displayName(LanguageCode.JA) shouldBe "미지키-된장찌개"
                    loaded.description(LanguageCode.JA) shouldBe "구수한 미지키-된장찌개"
                }
            }
        }

        given("Food 조회 — 포함 기피 성분 없음") {
            `when`("포함 기피 성분이 하나도 없는 음식을 저장하면") {
                then("정상 저장되고 빈 목록으로 복원된다") {
                    val id = saveFood("성분없음-흰밥", substances = emptyList())

                    val loaded = service.getReadyFood(id)
                    loaded.avoidanceSubstances shouldBe emptyList<FoodAvoidanceItem>()
                }
            }

            `when`("번역이 하나도 없는 음식을 저장하면") {
                then("빈 번역 맵으로 복원된다") {
                    val id = saveFood("번역없음-흰밥")

                    val loaded = service.getReadyFood(id)
                    loaded.nameTranslations shouldBe emptyMap<String, String>()
                    loaded.descriptionTranslations shouldBe emptyMap<String, String>()
                }
            }
        }

        given("Food 조회 — 단일 쿼리(N+1 없음)") {
            `when`("포함 기피 성분·번역이 여러 개인 음식을 조회하면") {
                then("성분이 JSON 컬럼에 인라인되어 성분 개수와 무관하게 음식 1 SQL 로 로드한다") {
                    val id = saveFood(
                        "N플러스원-부대찌개",
                        substances = listOf(
                            "EGG" to 70,
                            "SOY" to 100,
                            "PORK" to 90,
                            "WHEAT" to 60,
                        ),
                        nameTranslations = mapOf("en" to "Budae Jjigae", "ja" to "プデチゲ"),
                        descriptionTranslations = mapOf("en" to "Army stew."),
                    )
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    statistics.clear()

                    val loaded = service.getReadyFood(id)

                    loaded.avoidanceSubstances.orEmpty().size shouldBe 4
                    loaded.nameTranslations.size shouldBe 2
                    statistics.prepareStatementCount shouldBe 1
                }
            }
        }

        given("Food 목록 — 메뉴 목록 keyset 페이지네이션") {
            `when`("커서 없이 첫 페이지(20개)를 조회하면") {
                then("최신순(id 내림차순) 상위 20개를 반환한다") {
                    clearFoods()
                    val ids = (1..22).map { saveFood("목록정렬-메뉴$it") }

                    val page = service.getFoods(null, 20)

                    page.map { it.id } shouldBe ids.sortedDescending().take(20)
                }
            }

            `when`("커서를 지정해 다음 페이지를 조회하면") {
                then("id 가 커서보다 작은 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val ids = (1..5).map { saveFood("커서경계-메뉴$it") }
                    val cursor = ids.sorted()[2]

                    val page = service.getFoods(cursor, 20)

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

                    val page = service.getFoods(null, 20)

                    page.map { it.id } shouldBe listOf(last, first)
                }
            }
        }

        given("Food 검색 — 검색어 부분 일치(한국어명)") {
            `when`("한국어명 조각으로 검색하면") {
                then("한국어명에 그 조각을 포함하는 메뉴만 반환한다") {
                    clearFoods()
                    val stew = saveFood("김치찌개")
                    val friedRice = saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    val page = service.getFoodsByKeyword("김치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldContainExactlyInAnyOrder listOf(stew, friedRice)
                }
            }

            `when`("어떤 이름에도 없는 검색어로 검색하면") {
                then("빈 목록을 반환한다") {
                    clearFoods()
                    saveFood("김치찌개")

                    service.getFoodsByKeyword("파스타", LanguageCode.KO, null, 20) shouldBe emptyList<Food>()
                }
            }

            `when`("검색 결과에 커서를 지정하면") {
                then("id 가 커서보다 작은 매칭 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val ids = (1..3).map { saveFood("커서검색-김치$it") }
                    val cursor = ids.sorted()[2]

                    val page = service.getFoodsByKeyword("김치", LanguageCode.KO, cursor, 20)

                    page.map { it.id } shouldBe ids.filter { it < cursor }.sortedDescending()
                }
            }
        }

        given("Food 검색 — keyset 커서 경계 (US2)") {
            `when`("커서 안쪽에 매칭되지 않는 메뉴가 섞여 있으면") {
                then("id 가 커서보다 작은 매칭 항목만 최신순으로 반환한다") {
                    clearFoods()
                    val first = saveFood("커서혼합-김치찌개")
                    saveFood("커서혼합-된장찌개")
                    val second = saveFood("커서혼합-김치볶음밥")
                    saveFood("커서혼합-순두부찌개")
                    val cursor = saveFood("커서혼합-김치만두")

                    val page = service.getFoodsByKeyword("김치", LanguageCode.KO, cursor, 20)

                    page.map { it.id } shouldBe listOf(second, first)
                }
            }

            `when`("커서가 매칭 항목의 최소 id 이하이면") {
                then("빈 목록을 반환한다") {
                    clearFoods()
                    val smallest = saveFood("커서소진-김치찌개")
                    saveFood("커서소진-김치볶음밥")

                    service.getFoodsByKeyword("김치", LanguageCode.KO, smallest, 20) shouldBe emptyList<Food>()
                }
            }
        }

        given("Food 검색 — 검색어 부분 일치(요청 언어 번역명)") {
            `when`("영어 번역명 조각을 소문자로 검색하면 (lang=en)") {
                then("대소문자를 구분하지 않고 번역명 매칭 메뉴를 반환한다") {
                    clearFoods()
                    val bibimbap = saveFood("비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))
                    saveFood("된장찌개", nameTranslations = mapOf("en" to "Doenjang Stew"))

                    val page = service.getFoodsByKeyword("bibim", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(bibimbap)
                }
            }

            `when`("일본어 번역명에만 검색어가 있는 메뉴를 lang=en 으로 검색하면") {
                then("요청 언어가 아니므로 결과에 포함되지 않는다") {
                    clearFoods()
                    saveFood("냉면", nameTranslations = mapOf("ja" to "ネンミョン", "en" to "Cold Noodles"))

                    service.getFoodsByKeyword("ネンミョン", LanguageCode.EN, null, 20) shouldBe emptyList<Food>()
                }
            }

            `when`("번역명에만 있는 검색어를 lang=ko 로 검색하면") {
                then("ko 는 한국어명만 매칭하므로 결과에 포함되지 않는다") {
                    clearFoods()
                    saveFood("비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))

                    service.getFoodsByKeyword("Bibimbap", LanguageCode.KO, null, 20) shouldBe emptyList<Food>()
                }
            }

            `when`("한국어명과 요청 언어 번역명 양쪽에 검색어가 있으면") {
                then("결과에 한 번만 담긴다") {
                    clearFoods()
                    val id = saveFood("Bibim비빔밥", nameTranslations = mapOf("en" to "Bibimbap"))

                    val page = service.getFoodsByKeyword("bibim", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }
        }

        given("Food 검색 — 검색어의 패턴 특수문자는 리터럴로 매칭한다 (FR-003a)") {
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

                    val page = service.getFoodsByKeyword("%", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("percent"))
                }
            }

            `when`("검색어가 _ 하나이면") {
                then("임의 1문자 와일드카드가 아니라 이름에 _ 를 포함하는 메뉴만 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = service.getFoodsByKeyword("_", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("underscore"))
                }
            }

            `when`("검색어에 % 가 리터럴로 섞여 있으면 (50%)") {
                then("그 조각을 이름에 포함하는 메뉴를 부분 일치로 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = service.getFoodsByKeyword("50%", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("percent"))
                }
            }

            `when`("검색어 가운데에 _ 가 섞여 있으면 (김_치)") {
                then("임의 1문자로 해석하지 않아 김밥치즈 는 매칭되지 않는다") {
                    clearFoods()
                    saveFood("김치찌개")
                    saveFood("김밥치즈")
                    val underscore = saveFood("김_치")

                    val page = service.getFoodsByKeyword("김_치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(underscore)
                }
            }

            `when`("검색어가 이스케이프 문자 자체(백슬래시)이면") {
                then("이스케이프 문자도 리터럴로 취급해 백슬래시를 포함하는 메뉴만 반환한다") {
                    val seeded = seedWildcardFoods()

                    val page = service.getFoodsByKeyword("\\", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(seeded.getValue("backslash"))
                }
            }

            `when`("요청 언어 번역명 경로에서 검색어가 % 하나이면") {
                then("전체가 아니라 번역명에 % 를 포함하는 메뉴만 반환한다") {
                    clearFoods()
                    saveFood("일반세트", nameTranslations = mapOf("en" to "Normal Set"))
                    val sale = saveFood("세일세트", nameTranslations = mapOf("en" to "50% Off Set"))

                    val page = service.getFoodsByKeyword("%", LanguageCode.EN, null, 20)

                    page.map { it.id } shouldBe listOf(sale)
                }
            }
        }

        given("Food 검색 — 하이픈이 든 언어 코드(zh-Hans·zh-Hant) 번역명 매칭") {
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

                    val page = service.getFoodsByKeyword("简体", LanguageCode.ZH_HANS, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }

            `when`("번체 번역명 조각을 lang=zh-Hant 로 검색하면") {
                then("JSON 경로가 인용돼 번체 번역명으로 매칭한다") {
                    val id = seedChineseFood()

                    val page = service.getFoodsByKeyword("繁體", LanguageCode.ZH_HANT, null, 20)

                    page.map { it.id } shouldBe listOf(id)
                }
            }

            `when`("간체 번역명 조각을 lang=zh-Hant 로 교차 검색하면") {
                then("하이픈 코드에서도 언어 분리가 성립해 매칭되지 않는다") {
                    seedChineseFood()

                    service.getFoodsByKeyword("简体", LanguageCode.ZH_HANT, null, 20) shouldBe emptyList<Food>()
                }
            }
        }

        given("Food 검색 — 한국어명 경로의 대소문자 비구분 (FR-003)") {
            `when`("한국어명에 든 라틴 대문자를 소문자 검색어로 찾으면 (lang=KO)") {
                then("대소문자를 구분하지 않고 매칭한다") {
                    clearFoods()
                    val bbq = saveFood("BBQ 치킨")
                    saveFood("김치찌개")

                    val page = service.getFoodsByKeyword("bbq", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(bbq)
                }
            }

            `when`("한국어명에 든 라틴 소문자를 대문자 검색어로 찾으면 (lang=KO)") {
                then("대소문자를 구분하지 않고 매칭한다") {
                    clearFoods()
                    val latte = saveFood("Latte 라떼")
                    saveFood("김치찌개")

                    val page = service.getFoodsByKeyword("LATTE", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(latte)
                }
            }
        }

        given("Food 검색 — 소프트 삭제된 메뉴 제외") {
            `when`("검색어가 매칭되는 메뉴가 소프트 삭제되어 있으면") {
                then("네이티브 검색 쿼리도 ACTIVE 만 반환한다") {
                    clearFoods()
                    val alive = saveFood("소프트삭제검색-김치찌개")
                    val deleted = saveFood("소프트삭제검색-김치볶음밥")
                    val deletedEntity = foodJpaRepository.findById(deleted).get()
                    deletedEntity.delete()
                    foodJpaRepository.save(deletedEntity)

                    val page = service.getFoodsByKeyword("김치", LanguageCode.KO, null, 20)

                    page.map { it.id } shouldBe listOf(alive)
                }
            }
        }

        given("Food 매칭 — 한국어 이름 정확 일치 배치 조회") {
            `when`("여러 이름으로 한 번에 조회하면") {
                then("이름별 음식을 담은 맵을 반환하고 없는 이름은 빠진다") {
                    clearFoods()
                    val kimchi = saveFood("김치찌개")
                    val gukbap = saveFood("돼지국밥")

                    val found = service.getFoodsByKoreanNames(setOf("김치찌개", "돼지국밥", "없는메뉴"))

                    found.keys shouldBe setOf("김치찌개", "돼지국밥")
                    found.getValue("김치찌개").id shouldBe kimchi
                    found.getValue("돼지국밥").id shouldBe gukbap
                }
            }

            `when`("미완성(INCOMPLETE) 음식이 이름과 일치하면") {
                then("스캔 매칭 대상이므로 포함된다") {
                    clearFoods()
                    service.createIncomplete(setOf("우주라면"))

                    val found = service.getFoodsByKoreanNames(setOf("우주라면"))

                    found.getValue("우주라면").isReady() shouldBe false
                }
            }

            `when`("정규화되지 않은 이름의 음식이 남아 있으면") {
                then("정확 일치가 아니므로 매칭되지 않는다") {
                    clearFoods()
                    val normalized = saveFood("국밥")
                    saveFood("국 밥")

                    val found = service.getFoodsByKoreanNames(setOf("국밥"))

                    found.getValue("국밥").id shouldBe normalized
                    found.keys shouldBe setOf("국밥")
                }
            }

            `when`("소프트 삭제된 음식만 이름이 일치하면") {
                then("@SQLRestriction 으로 제외된다") {
                    clearFoods()
                    val id = saveFood("삭제된김밥")
                    val entity = foodJpaRepository.findById(id).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    service.getFoodsByKoreanNames(setOf("삭제된김밥")) shouldBe emptyMap<String, Food>()
                }
            }

            `when`("빈 이름 집합으로 조회하면") {
                then("빈 맵을 반환한다(쿼리 없음)") {
                    service.getFoodsByKoreanNames(emptySet()) shouldBe emptyMap<String, Food>()
                }
            }
        }

        given("Food 생성 — 미완성 음식 일괄 생성") {
            `when`("스캔 miss 이름들을 한 번에 등록하면") {
                then("모두 INCOMPLETE 로 저장되고 이름별 음식 맵을 돌려준다") {
                    clearFoods()

                    val created = service.createIncomplete(setOf("마라샹궈", "우주라면", "탕후루"))

                    created.keys shouldBe setOf("마라샹궈", "우주라면", "탕후루")
                    created.values.forEach {
                        it.isReady() shouldBe false
                    }
                    foodJpaRepository.count() shouldBe 3
                }
            }

            `when`("이미 있는 이름과 새 이름이 섞여 있으면") {
                then("기존 음식은 재사용하고 새 이름만 삽입한다") {
                    clearFoods()
                    val existingId = service.createIncomplete(setOf("마라탕")).getValue("마라탕").id

                    val created = service.createIncomplete(setOf("마라탕", "탕수육"))

                    created.getValue("마라탕").id shouldBe existingId
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("완성(READY) 음식과 이름이 같으면") {
                then("미완성으로 덮어쓰지 않고 기존 음식을 그대로 돌려준다") {
                    clearFoods()
                    val readyId = saveFood("완성-비빔밥")

                    val created = service.createIncomplete(setOf("완성-비빔밥"))

                    created.getValue("완성-비빔밥").id shouldBe readyId
                    created.getValue("완성-비빔밥").isReady() shouldBe true
                    foodJpaRepository.count() shouldBe 1
                }
            }

            `when`("빈 집합으로 호출하면") {
                then("쿼리 없이 빈 맵을 돌려준다") {
                    service.createIncomplete(emptySet()) shouldBe emptyMap<String, Food>()
                }
            }

            `when`("소프트 삭제된 동명 음식이 섞여 있으면") {
                then("되살리지 않고 그 이름만 결과에서 빠지며, 같은 배치의 새 이름은 정상 등록된다") {
                    clearFoods()
                    val ghostId = saveFood("유령-라면")
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)

                    val created = service.createIncomplete(setOf("유령-라면", "신규-라면"))

                    created shouldNotContainKey "유령-라면"
                    created.getValue("신규-라면").shouldNotBeNull()
                }
            }
        }

        given("Food upsert — 미완성 음식은 미조사 센티널로 저장") {
            `when`("createIncomplete 로 미완성 음식을 적재하면") {
                then("upsert 경로가 기피성분 NULL(미조사)·맵기 -1 로 저장한다") {
                    clearFoods()

                    val created = service.createIncomplete(setOf("센티널-우주라면")).getValue("센티널-우주라면")

                    created.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                    created.avoidanceSubstances shouldBe null
                    created.spiciness shouldBe Food.SPICINESS_UNASSESSED
                }
            }
        }

        given("Food 적재 — 관리자 시드(seedIncomplete)") {
            `when`("전부 새 이름이면") {
                then("모두 INCOMPLETE 로 생성되고 created 로 센다") {
                    clearFoods()

                    val result = service.seedIncomplete(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 3, skipped = 0)
                    foodJpaRepository.count() shouldBe 3
                    service.getFoodsByKoreanNames(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))
                        .values.forEach { it.isReady() shouldBe false }
                }
            }

            `when`("정규화되지 않은 표기가 섞여 있으면") {
                then("정규화(NFC·한글만)된 이름으로 저장·중복 판정한다") {
                    clearFoods()

                    val result = service.seedIncomplete(setOf("김치 찌개", "김치찌개", "Kimchi 김치찌개!", "abc123"))

                    result shouldBe SeedIncompleteResult(requested = 1, created = 1, skipped = 0)
                    service.getFoodsByKoreanNames(setOf("김치찌개")).keys shouldBe setOf("김치찌개")
                    foodJpaRepository.count() shouldBe 1
                }
            }

            `when`("기존 이름과 새 이름이 섞여 있으면") {
                then("새 이름만 생성하고 기존은 skipped 로 센다") {
                    clearFoods()
                    val existingId = saveFood("시드비빔밥")

                    val result = service.seedIncomplete(setOf("시드비빔밥", "시드김치찌개", "시드잡채"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 2, skipped = 1)
                    foodJpaRepository.count() shouldBe 3
                    service.getFoodsByKoreanNames(setOf("시드비빔밥")).getValue("시드비빔밥").id shouldBe existingId
                }
            }

            `when`("전부 기존 이름이면") {
                then("생성 없이 skipped 로만 세고 성공한다") {
                    clearFoods()
                    saveFood("시드국밥")
                    saveFood("시드냉면")

                    val result = service.seedIncomplete(setOf("시드국밥", "시드냉면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("빈 집합이거나 정규화 후 남는 이름이 없으면") {
                then("쿼리 없이 (0,0,0) 을 돌려준다") {
                    service.seedIncomplete(emptySet()) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                    service.seedIncomplete(setOf("abc", "123", "  ")) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                }
            }

            `when`("같은 목록으로 두 번 적재하면") {
                then("두 번째는 created=0 으로 성공하고 행 수가 늘지 않는다") {
                    clearFoods()
                    val names = setOf("멱등마라탕", "멱등탕수육")

                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 2, skipped = 0)
                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("소프트 삭제된 동명 음식만 있으면") {
                then("되살리지도 새로 만들지도 않고 skipped 로 집계한다") {
                    clearFoods()
                    val ghostId = saveFood("유령시드라면")
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)

                    val result = service.seedIncomplete(setOf("유령시드라면", "생존시드라면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 1, skipped = 1)
                    service.getFoodsByKoreanNames(setOf("생존시드라면")).keys shouldBe setOf("생존시드라면")
                }
            }

            `when`("동일 목록을 두 스레드가 동시에 적재하면") {
                then("각 이름은 정확히 한 행만 저장되고 created 합계도 실제 생성 수와 일치한다") {
                    clearFoods()
                    val names = setOf("경합마라탕", "경합쌀국수", "경합분짜")
                    val barrier = CyclicBarrier(2)

                    val results = (1..2).map {
                        CompletableFuture.supplyAsync {
                            barrier.await()
                            service.seedIncomplete(names)
                        }
                    }.map { it.join() }

                    results.forEach { it.requested shouldBe 3 }
                    results.sumOf { it.created } shouldBe 3
                    foodJpaRepository.count() shouldBe 3
                    service.getFoodsByKoreanNames(names).keys shouldBe names
                }
            }
        }

        given("Food 노출 — 미완성 음식 노출 차단(serving gate)") {
            `when`("미완성 음식이 섞인 채 메뉴 목록을 조회하면") {
                then("READY 음식만 반환한다") {
                    clearFoods()
                    val ready = saveFood("완성-비빔밥")
                    service.createIncomplete(setOf("미완성-우주라면"))

                    service.getFoods(null, 20).map { it.id } shouldBe listOf(ready)
                }
            }

            `when`("미완성 음식을 id 로 상세 조회하면") {
                then("FOOD_NOT_FOUND 예외를 던진다") {
                    clearFoods()
                    val incompleteId = service.createIncomplete(setOf("미완성-마라탕")).getValue("미완성-마라탕").id

                    shouldThrow<BusinessException> {
                        service.getReadyFood(incompleteId)
                    }.errorCode shouldBe ErrorCode.FOOD_NOT_FOUND
                }
            }

            `when`("미완성 음식의 이름이 검색어와 일치하면") {
                then("네이티브 검색 쿼리도 READY 음식만 반환한다") {
                    clearFoods()
                    val ready = saveFood("완성-라면")
                    service.createIncomplete(setOf("미완성-라면"))

                    service.getFoodsByKeyword("라면", LanguageCode.KO, null, 20).map { it.id } shouldBe listOf(ready)
                }
            }
        }

        given("Food 스키마 — 상태 컬럼은 DB 가 후보값을 고정한다") {
            `when`("status 에 정의되지 않은 값을 직접 넣으면") {
                then("DB 가 거부한다(오타 유입 차단)") {
                    shouldThrow<java.sql.SQLException> {
                        dataSource.connection.use { c ->
                            c.prepareStatement(
                                "INSERT INTO food (korean_name, description, spiciness, name_translations, " +
                                    "description_translations, avoidance_substances, content_status, status, created_at, updated_at) " +
                                    "VALUES ('오타상태', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIV', NOW(6), NOW(6))",
                            ).use { it.executeUpdate() }
                        }
                    }
                }
            }

            `when`("content_status 에 정의되지 않은 값을 직접 넣으면") {
                then("DB 가 거부한다") {
                    shouldThrow<java.sql.SQLException> {
                        dataSource.connection.use { c ->
                            c.prepareStatement(
                                "INSERT INTO food (korean_name, description, spiciness, name_translations, " +
                                    "description_translations, avoidance_substances, content_status, status, created_at, updated_at) " +
                                    "VALUES ('오타완성상태', '설명', 0, '{}', '{}', '[]', 'REDY', 'ACTIVE', NOW(6), NOW(6))",
                            ).use { it.executeUpdate() }
                        }
                    }
                }
            }
        }
    }
}
