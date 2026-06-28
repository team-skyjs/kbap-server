package com.meogo.api.persistence.food

import com.meogo.api.food.Food
import com.meogo.api.food.FoodIngredient
import com.meogo.api.food.Ingredient
import com.meogo.api.food.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
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
    private lateinit var jpaRepository: FoodJpaRepository

    init {
        fun persistStew(koreanName: String): Long {
            val clam = Ingredient(
                koreanName = "바지락 조개",
                names = mapOf(LanguageCode.EN to "Manila clam", LanguageCode.JA to "アサリ"),
                iconRef = "clam.png",
            )
            val doenjang = Ingredient(
                koreanName = "된장",
                names = mapOf(LanguageCode.EN to "Soybean paste", LanguageCode.JA to "テンジャン"),
                iconRef = null,
            )
            val tofu = Ingredient(
                koreanName = "두부",
                names = mapOf(LanguageCode.EN to "Tofu", LanguageCode.JA to "豆腐"),
                iconRef = null,
            )
            val food = Food(
                koreanName = koreanName,
                names = mapOf(LanguageCode.EN to "Doenjang Stew", LanguageCode.JA to "テンジャンチゲ"),
                imageRef = "doenjang.png",
                ingredients = listOf(
                    FoodIngredient(ingredient = clam, inclusionPercent = 50, displayOrder = 2),
                    FoodIngredient(ingredient = doenjang, inclusionPercent = 100, displayOrder = 0),
                    FoodIngredient(ingredient = tofu, inclusionPercent = 90, displayOrder = 1),
                ),
            )
            return jpaRepository.save(FoodJpaEntity.from(food)).id
        }

        given("Food 저장소 어댑터") {
            `when`("한국어 메뉴명으로 조회하면") {
                then("음식·번역·재료를 fetch join 으로 함께 로드하고 displayOrder 로 정렬한다") {
                    persistStew("된장찌개")

                    val loaded = adapter.findByKoreanName("된장찌개")
                    loaded.shouldNotBeNull()
                    loaded.koreanName shouldBe "된장찌개"
                    loaded.nameFor(LanguageCode.EN) shouldBe "Doenjang Stew"
                    loaded.nameFor(LanguageCode.JA) shouldBe "テンジャンチゲ"
                    loaded.nameFor(LanguageCode.ES) shouldBe "된장찌개"
                    loaded.imageRef shouldBe "doenjang.png"

                    loaded.ingredients.map { it.ingredient.koreanName }
                        .shouldContainExactly("된장", "두부", "바지락 조개")
                    loaded.ingredients.map { it.inclusionPercent }
                        .shouldContainExactly(100, 90, 50)

                    val clam = loaded.ingredients.first { it.ingredient.koreanName == "바지락 조개" }
                    clam.ingredient.nameFor(LanguageCode.EN) shouldBe "Manila clam"
                    clam.ingredient.iconRef shouldBe "clam.png"
                }
            }

            `when`("앞뒤 공백이 있는 메뉴명으로 조회하면") {
                then("trim 후 매칭한다") {
                    persistStew("김치찌개")

                    adapter.findByKoreanName("  김치찌개  ").shouldNotBeNull()
                }
            }

            `when`("수록되지 않은 메뉴명으로 조회하면") {
                then("null 을 반환한다") {
                    adapter.findByKoreanName("존재하지않는메뉴") shouldBe null
                }
            }

            `when`("저장된 음식을 소프트 삭제하면") {
                then("@SQLRestriction 으로 조회에서 제외돼 null 이 반환된다") {
                    val savedId = persistStew("순두부찌개")

                    val entity = jpaRepository.findById(savedId).get()
                    entity.delete()
                    jpaRepository.save(entity)

                    adapter.findByKoreanName("순두부찌개").shouldBeNull()
                }
            }
        }
    }
}
