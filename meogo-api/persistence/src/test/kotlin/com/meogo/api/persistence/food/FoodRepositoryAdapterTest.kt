package com.meogo.api.persistence.food

import com.meogo.api.food.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class FoodRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodRepositoryAdapter

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var ingredientJpaRepository: IngredientJpaRepository

    @Autowired
    private lateinit var foodNameTranslationJpaRepository: FoodNameTranslationJpaRepository

    @Autowired
    private lateinit var ingredientNameTranslationJpaRepository: IngredientNameTranslationJpaRepository

    init {
        fun saveIngredient(koreanName: String, iconRef: String? = null): IngredientJpaEntity =
            ingredientJpaRepository.save(IngredientJpaEntity(koreanName = koreanName, iconRef = iconRef))

        fun saveFood(
            koreanName: String,
            imageRef: String? = null,
            items: List<Pair<IngredientJpaEntity, Int>> = emptyList(),
        ): Long {
            val food = FoodJpaEntity(
                koreanName = koreanName,
                imageRef = imageRef,
                foodIngredients = items.map { (ingredient, percent) ->
                    FoodIngredientJpaEntity(
                        ingredient = ingredient,
                        inclusionPercent = percent,
                    )
                }.toMutableSet(),
            )
            return foodJpaRepository.save(food).id
        }

        given("Food 저장소 어댑터 — 구조 복원") {
            `when`("한국어 메뉴명으로 조회하면") {
                then("음식·재료를 복원한다(정렬은 서비스단 책임, 번역 미포함)") {
                    val doenjang = saveIngredient("된장-recon")
                    val tofu = saveIngredient("두부-recon")
                    val clam = saveIngredient("바지락-recon", iconRef = "clam.png")
                    saveFood(
                        "구조복원-된장찌개",
                        imageRef = "doenjang.png",
                        items = listOf(
                            clam to 50,
                            doenjang to 100,
                            tofu to 90,
                        ),
                    )

                    val loaded = adapter.findByKoreanName("구조복원-된장찌개")
                    loaded.shouldNotBeNull()
                    loaded.imageRef shouldBe "doenjang.png"
                    loaded.ingredients.map { it.ingredient.koreanName }
                        .shouldContainExactlyInAnyOrder("된장-recon", "두부-recon", "바지락-recon")
                    loaded.ingredients.map { it.inclusionPercent }
                        .shouldContainExactlyInAnyOrder(100, 90, 50)
                    loaded.ingredients.first { it.ingredient.koreanName == "바지락-recon" }
                        .ingredient.iconRef shouldBe "clam.png"
                }
            }

            `when`("앞뒤 공백이 있는 메뉴명으로 조회하면") {
                then("trim 후 매칭한다") {
                    saveFood("trim-김치찌개", items = listOf(saveIngredient("두부-trim") to 80))

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
                    val savedId = saveFood("삭제-순두부찌개", items = listOf(saveIngredient("두부-del") to 95))

                    val entity = foodJpaRepository.findById(savedId).get()
                    entity.delete()
                    foodJpaRepository.save(entity)

                    adapter.findByKoreanName("삭제-순두부찌개").shouldBeNull()
                }
            }
        }

        given("공유 재료 — 복제 금지") {
            `when`("같은 재료를 쓰는 음식 2개를 저장하면") {
                then("재료 row 는 1개만 생기고 두 음식 모두 같은 ingredient_id 를 참조한다") {
                    val tofu = saveIngredient("두부-shared")
                    val before = ingredientJpaRepository.count()

                    saveFood("공유A-된장찌개", items = listOf(tofu to 90))
                    saveFood("공유B-순두부찌개", items = listOf(tofu to 95))

                    ingredientJpaRepository.count() shouldBe before

                    val a = adapter.findByKoreanName("공유A-된장찌개").shouldNotBeNull()
                    val b = adapter.findByKoreanName("공유B-순두부찌개").shouldNotBeNull()
                    a.ingredients.single().ingredient.id shouldBe tofu.id
                    b.ingredients.single().ingredient.id shouldBe tofu.id
                }
            }
        }

        given("번역 조회 — 요청 언어만") {
            `when`("요청 언어 번역이 있으면") {
                then("해당 언어 번역을 반환한다") {
                    val tofu = saveIngredient("두부-tx")
                    val foodId = saveFood("번역있음", items = listOf(tofu to 90))
                    foodNameTranslationJpaRepository.save(
                        FoodNameTranslationJpaEntity(foodId = foodId, langCode = "en", name = "Doenjang Stew"),
                    )
                    ingredientNameTranslationJpaRepository.save(
                        IngredientNameTranslationJpaEntity(ingredientId = tofu.id, langCode = "en", name = "Tofu"),
                    )

                    adapter.findFoodNameTranslation(foodId, LanguageCode.EN) shouldBe "Doenjang Stew"
                    adapter.findIngredientNameTranslations(listOf(tofu.id), LanguageCode.EN) shouldBe
                        mapOf(tofu.id to "Tofu")
                }
            }

            `when`("요청 언어 번역이 없으면") {
                then("null·빈 맵을 반환한다(application 이 ko 로 폴백)") {
                    val tofu = saveIngredient("두부-notx")
                    val foodId = saveFood("번역없음", items = listOf(tofu to 80))

                    adapter.findFoodNameTranslation(foodId, LanguageCode.JA).shouldBeNull()
                    adapter.findIngredientNameTranslations(listOf(tofu.id), LanguageCode.JA) shouldBe emptyMap()
                }
            }

            `when`("DB 에 잘못된 lang_code 번역 row 가 있으면") {
                then("요청 언어와 일치하지 않아 조용히 KO 로 접히지 않고 무시된다") {
                    val foodId = saveFood("잘못된코드")
                    foodNameTranslationJpaRepository.save(
                        FoodNameTranslationJpaEntity(foodId = foodId, langCode = "xx", name = "bogus"),
                    )

                    adapter.findFoodNameTranslation(foodId, LanguageCode.EN).shouldBeNull()
                }
            }
        }

        given("9개 대상 언어 전수 — 코드↔쿼리 라운드트립") {
            val targetLanguages = LanguageCode.entries.filter { it != LanguageCode.KO }

            `when`("음식·재료에 9개 언어 번역을 모두 저장하면") {
                then("각 언어로 조회할 때 해당 언어 번역을 정확히 반환한다") {
                    targetLanguages.size shouldBe 9

                    val ingredient = saveIngredient("두부-9lang")
                    val foodId = saveFood("9개국어", items = listOf(ingredient to 90))
                    targetLanguages.forEach { lang ->
                        foodNameTranslationJpaRepository.save(
                            FoodNameTranslationJpaEntity(foodId = foodId, langCode = lang.code, name = "food-${lang.code}"),
                        )
                        ingredientNameTranslationJpaRepository.save(
                            IngredientNameTranslationJpaEntity(ingredientId = ingredient.id, langCode = lang.code, name = "ing-${lang.code}"),
                        )
                    }

                    targetLanguages.forEach { lang ->
                        adapter.findFoodNameTranslation(foodId, lang) shouldBe "food-${lang.code}"
                        adapter.findIngredientNameTranslations(listOf(ingredient.id), lang) shouldBe
                            mapOf(ingredient.id to "ing-${lang.code}")
                    }
                }
            }
        }
    }
}
