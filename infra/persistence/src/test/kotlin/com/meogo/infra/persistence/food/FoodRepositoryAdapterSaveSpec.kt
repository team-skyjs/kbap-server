package com.meogo.infra.persistence.food

import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodRepositoryAdapterSaveSpec : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodRepositoryAdapter

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    init {
        fun foodOf(
            koreanName: String,
            description: String = "구수한 $koreanName",
            imageRef: String? = null,
            spiciness: Int = 0,
            nameTranslations: Map<LanguageCode, String> = emptyMap(),
            descriptionTranslations: Map<LanguageCode, String> = emptyMap(),
            substances: List<Pair<String, Int>> = emptyList(),
        ): Food =
            Food.create(
                content = FoodContent(
                    name = LocalizedText(korean = koreanName, translations = nameTranslations),
                    description = LocalizedText(korean = description, translations = descriptionTranslations),
                ),
                imageRef = imageRef,
                spiciness = FoodSpiciness(spiciness),
                avoidanceSubstances = substances.map { (code, percent) ->
                    FoodAvoidanceSubstance(
                        substanceCode = AvoidanceSubstanceCodeRef(code),
                        inclusionProbability = percent,
                    )
                },
            )

        fun rowCount(koreanName: String): Int =
            foodJpaRepository.findAll().count { it.koreanName == koreanName }

        beforeEach {
            foodJpaRepository.deleteAll()
        }

        given("Food 저장소 어댑터 save — 신규 insert") {
            `when`("수록되지 않은 korean_name Food 를 save 하면") {
                then("food row 가 1개 생성되고 반환 Food 에 영속 id·필드가 저장된다") {
                    val food = foodOf(
                        "insert-된장찌개",
                        description = "된장으로 끓인 찌개",
                        imageRef = "doenjang.png",
                        spiciness = 4,
                        nameTranslations = mapOf(LanguageCode.EN to "Doenjang Stew"),
                        descriptionTranslations = mapOf(LanguageCode.EN to "A hearty stew."),
                        substances = listOf("SOYBEAN" to 100, "TOFU" to 90),
                    )

                    val saved = adapter.save(food)

                    saved.id.shouldNotBeNull()
                    rowCount("insert-된장찌개") shouldBe 1

                    val loaded = adapter.findByKoreanName("insert-된장찌개").shouldNotBeNull()
                    loaded.imageRef shouldBe "doenjang.png"
                    loaded.spiciness.value shouldBe 4
                    loaded.content.description.korean shouldBe "된장으로 끓인 찌개"
                    loaded.content.name.translations shouldContainExactly mapOf(LanguageCode.EN to "Doenjang Stew")
                    loaded.content.description.translations shouldContainExactly mapOf(LanguageCode.EN to "A hearty stew.")
                    loaded.avoidanceSubstances.map { it.substanceCode.value }
                        .shouldContainExactlyInAnyOrder("SOYBEAN", "TOFU")
                    loaded.avoidanceSubstances.map { it.inclusionProbability }
                        .shouldContainExactlyInAnyOrder(100, 90)
                }
            }
        }

        given("Food 저장소 어댑터 save — insert 전용(업서트하지 않음)") {
            `when`("이미 저장된 korean_name 으로 필드를 바꿔 다시 save 하면") {
                then("기존 row 를 갱신하지 않고 별도 row 를 삽입하며 기존 row 값은 보존된다") {
                    val first = adapter.save(
                        foodOf("insertonly-김치찌개", description = "첫 설명", imageRef = "old.png", spiciness = 2),
                    )
                    val second = adapter.save(
                        foodOf("insertonly-김치찌개", description = "둘째 설명", imageRef = "new.png", spiciness = 7),
                    )

                    rowCount("insertonly-김치찌개") shouldBe 2
                    second.id shouldNotBe first.id

                    val firstReloaded = foodJpaRepository.findById(first.id!!).orElseThrow()
                    firstReloaded.description shouldBe "첫 설명"
                    firstReloaded.imageRef shouldBe "old.png"
                    firstReloaded.spiciness shouldBe 2
                }
            }
        }
    }
}
