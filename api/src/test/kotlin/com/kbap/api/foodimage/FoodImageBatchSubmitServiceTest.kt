package com.kbap.api.foodimage

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodImageBatchSubmitServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var submitService: FoodImageBatchSubmitService

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var batchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var itemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    init {
        fun clearAll() {
            itemRepository.deleteAll()
            batchRepository.deleteAll()
            foodRepository.deleteAll()
            fakeClient.reset()
        }

        beforeContainer { clearAll() }
        afterSpec { clearAll() }

        given("이미지 일괄 제출 — 분할·메타 기록") {
            `when`("이미지 없는 음식 105건을 제출하면") {
                then("100건 단위 2배치로 분할 제출되고 SUBMITTED/PENDING 메타가 기록된다") {
                    val foods = foodRepository.saveAll((1..105).map { Food.incomplete("제출음식$it") })

                    val result = submitService.submitMissingImages()

                    result.submittedBatchCount shouldBe 2
                    result.submittedFoodCount shouldBe 105
                    fakeClient.submitted.map { it.size } shouldContainExactly listOf(100, 5)

                    val batches = batchRepository.findAll()
                    batches.size shouldBe 2
                    batches.all { it.batchStatus == ImageBatchStatus.SUBMITTED } shouldBe true
                    batches.all { it.promptVersion.isNotBlank() && it.model.isNotBlank() } shouldBe true

                    val items = itemRepository.findAll()
                    items.size shouldBe 105
                    items.all { it.itemStatus == ImageBatchItemStatus.PENDING } shouldBe true
                    items.map { it.foodId }.sorted() shouldBe foods.map { it.id }.sorted()
                }
            }

            `when`("제출된 entries 를 보면") {
                then("custom_id 는 food PK 이고 프롬프트에 음식 이름이 들어 있다") {
                    val food = foodRepository.save(Food.incomplete("마라샹궈"))

                    submitService.submitMissingImages()

                    val entry = fakeClient.submitted.single().single()
                    entry.customId shouldBe food.id.toString()
                    entry.prompt shouldContain "마라샹궈"
                }
            }

            `when`("이미지가 필요한 음식이 0건이면") {
                then("아무 배치도 만들지 않고 0/0 으로 정상 응답한다") {
                    val result = submitService.submitMissingImages()

                    result.submittedBatchCount shouldBe 0
                    result.submittedFoodCount shouldBe 0
                    fakeClient.submitted.size shouldBe 0
                    batchRepository.count() shouldBe 0
                }
            }
        }

        given("이미지 일괄 제출 — claim-first 실패 복구") {
            `when`("OpenAI 제출이 실패하면") {
                then("선점(SUBMITTING·PENDING)을 FAILED 로 즉시 해제해 음식이 다음 제출 후보로 돌아온다") {
                    val food = foodRepository.save(Food.incomplete("제출실패음식"))
                    fakeClient.submitFailure = RuntimeException("openai down")

                    runCatching { submitService.submitMissingImages() }.isFailure shouldBe true

                    batchRepository.findAll().single().batchStatus shouldBe ImageBatchStatus.FAILED
                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.FAILED
                    fakeClient.submitFailure = null
                    foodRepository.findImageCandidates().map { it.id } shouldBe listOf(food.id)
                }
            }

            `when`("제출이 성공하면") {
                then("배치는 openai_batch_id 를 얻고 SUBMITTED 로 확정된다") {
                    foodRepository.save(Food.incomplete("확정음식"))

                    submitService.submitMissingImages()

                    val batch = batchRepository.findAll().single()
                    batch.batchStatus shouldBe ImageBatchStatus.SUBMITTED
                    batch.openaiBatchId!! shouldContain "batch_"
                }
            }
        }

        given("이미지 일괄 제출 — 음식당 진행 중 작업 1개(DB UNIQUE)") {
            `when`("같은 음식의 PENDING 항목을 두 번 저장하려 하면(동시 제출 경합 시뮬레이션)") {
                then("pending_food_id 생성열 UNIQUE 가 두 번째 저장을 거부한다") {
                    val food = foodRepository.save(Food.incomplete("경합음식"))
                    val batch1 = batchRepository.save(ImageBatch(promptVersion = "v1", model = "m"))
                    val batch2 = batchRepository.save(ImageBatch(promptVersion = "v1", model = "m"))
                    itemRepository.saveAndFlush(ImageBatchItem(batchId = batch1.id, foodId = food.id))

                    runCatching {
                        itemRepository.saveAndFlush(ImageBatchItem(batchId = batch2.id, foodId = food.id))
                    }.exceptionOrNull() shouldNotBe null
                }
            }
        }

        given("이미지 일괄 제출 — 멱등(중복 제출 가드)") {
            `when`("같은 상태에서 제출을 연속 두 번 호출하면") {
                then("두 번째는 진행 중 배치 포함이라 0건 제출된다") {
                    foodRepository.save(Food.incomplete("연타음식"))

                    val first = submitService.submitMissingImages()
                    val second = submitService.submitMissingImages()

                    first.submittedFoodCount shouldBe 1
                    second.submittedFoodCount shouldBe 0
                    second.submittedBatchCount shouldBe 0
                    itemRepository.count() shouldBe 1
                }
            }

            `when`("PENDING_IMAGE(텍스트 완료) 음식과 INCOMPLETE 음식이 섞여 있으면") {
                then("상태와 무관하게 이미지 없는 둘 다 제출된다") {
                    foodRepository.save(Food.incomplete("미완음식"))
                    foodRepository.save(
                        Food.incomplete("텍스트완료음식").apply { contentStatus = FoodContentStatus.PENDING_IMAGE },
                    )

                    submitService.submitMissingImages().submittedFoodCount shouldBe 2
                }
            }
        }
    }
}
