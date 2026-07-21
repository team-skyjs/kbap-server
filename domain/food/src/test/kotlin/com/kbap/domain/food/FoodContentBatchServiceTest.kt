package com.kbap.domain.food

import com.kbap.core.lang.LanguageCode
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest(classes = [FoodTestApp::class])
@Import(MySqlContainerConfig::class)
class FoodContentBatchServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: FoodContentBatchService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }
            .associate { it.code to "t-${it.code}" }

        fun clear() = foodJpaRepository.deleteAll()

        fun saveIncomplete(koreanName: String): Long =
            foodJpaRepository.save(Food.incomplete(koreanName)).id

        fun saveReady(koreanName: String): Long =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = FoodContentStatus.READY),
            ).id

        given("getIncompleteFoods — INCOMPLETE 만 id 오름차순 청크") {
            `when`("INCOMPLETE 5건에서 afterId=null, size=3 으로 조회하면") {
                then("가장 작은 id 3건을 오름차순으로 반환한다") {
                    clear()
                    val ids = (1..5).map { saveIncomplete("청크-음식$it") }.sorted()

                    val chunk = service.getIncompleteFoods(afterId = null, size = 3)

                    chunk.map { it.id } shouldBe ids.take(3)
                }
            }
        }

        given("getIncompleteFoods — 키셋(afterId 이후)") {
            `when`("afterId 로 첫 청크의 마지막 id 를 주면") {
                then("그 id 이후만 반환한다(중복·건너뜀 없음)") {
                    clear()
                    val ids = (1..5).map { saveIncomplete("키셋-음식$it") }.sorted()

                    val first = service.getIncompleteFoods(afterId = null, size = 2)
                    val second = service.getIncompleteFoods(afterId = first.last().id, size = 2)

                    first.map { it.id } shouldBe ids.take(2)
                    second.map { it.id } shouldBe ids.drop(2).take(2)
                }
            }
        }

        given("getIncompleteFoods — READY 제외") {
            `when`("READY 와 INCOMPLETE 가 섞여 있으면") {
                then("INCOMPLETE 만 반환한다") {
                    clear()
                    saveReady("완성-김치찌개")
                    val incompleteId = saveIncomplete("미완성-된장찌개")

                    val chunk = service.getIncompleteFoods(afterId = null, size = 10)

                    chunk.map { it.id } shouldBe listOf(incompleteId)
                }
            }

            `when`("INCOMPLETE 가 하나도 없으면") {
                then("빈 목록을 반환한다") {
                    clear()
                    saveReady("완성-순두부")

                    service.getIncompleteFoods(afterId = null, size = 10).shouldBeEmpty()
                }
            }
        }

        given("completeContent — 완비 시 저장 + READY 전이") {
            `when`("스텝이 사진·설명·번역을 채우고 기피성분 매핑이 있으면") {
                then("채운 필드를 저장하고 READY 로 전이하며 true 를 반환한다") {
                    clear()
                    val id = saveIncomplete("완비-부대찌개")
                    val food = service.getIncompleteFoods(afterId = null, size = 1).single()
                    food.imageRef = "s3://img/budae.jpg"
                    food.description = "얼큰한 부대찌개"
                    food.nameTranslations = targets
                    food.descriptionTranslations = targets

                    val transitioned = service.completeContent(food, hasAvoidanceMapping = true)

                    transitioned shouldBe true
                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.contentStatus shouldBe FoodContentStatus.READY
                    reloaded.imageRef shouldBe "s3://img/budae.jpg"
                    reloaded.description shouldBe "얼큰한 부대찌개"
                }
            }
        }

        given("completeContent — 미완비 시 저장만") {
            `when`("사진만 채우고 번역·매핑이 없으면") {
                then("채운 필드는 저장하되 INCOMPLETE 를 유지하고 false 를 반환한다") {
                    clear()
                    val id = saveIncomplete("미완비-청국장")
                    val food = service.getIncompleteFoods(afterId = null, size = 1).single()
                    food.imageRef = "s3://img/cheonggukjang.jpg"

                    val transitioned = service.completeContent(food, hasAvoidanceMapping = false)

                    transitioned shouldBe false
                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                    reloaded.imageRef shouldBe "s3://img/cheonggukjang.jpg"
                }
            }
        }
    }
}
