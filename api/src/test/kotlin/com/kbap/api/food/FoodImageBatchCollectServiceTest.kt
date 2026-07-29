package com.kbap.api.food

import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.domain.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodImageBatchCollectServiceTest : BehaviorSpec() {
    companion object {
        val FOOD_IMAGE_KEY_PATTERN = Regex("""^images/food/[0-9a-f]{12}_[0-9a-f]{16}\.png$""")
    }

    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var collectService: FoodImageBatchCollectService

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var batchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var itemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    @Autowired
    private lateinit var fakeStorage: FakeStorageObjectStore

    @Autowired
    private lateinit var costListener: RecordingLlmCostListener

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }
            .associate { it.code to "t-${it.code}" }

        fun clearAll() {
            itemRepository.deleteAll()
            batchRepository.deleteAll()
            foodRepository.deleteAll()
            fakeClient.reset()
            fakeStorage.heads.clear()
            costListener.events.clear()
        }

        fun savePendingImage(name: String): Food =
            foodRepository.save(
                Food.incomplete(name).apply {
                    description = "구수한 $name"
                    spiciness = 2
                    nameTranslations = targets
                    descriptionTranslations = targets
                    avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100))
                    transitionByContentState()
                },
            )

        fun saveSubmittedBatch(vararg foodIds: Long): ImageBatch {
            val batch = batchRepository.save(
                ImageBatch(
                    openaiBatchId = "batch_test_${System.nanoTime()}",
                    batchStatus = ImageBatchStatus.SUBMITTED,
                    promptVersion = "v1",
                    model = "gpt-image-2",
                ),
            )
            foodIds.forEach { itemRepository.save(ImageBatchItem(batchId = batch.id, foodId = it)) }
            return batch
        }

        fun completed(outputFileId: String) =
            FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.COMPLETED, outputFileId, "file_err")

        fun okResult(foodId: Long, bytes: ByteArray = byteArrayOf(1, 2, 3)) =
            FoodImageBatchClient.Result(
                customId = foodId.toString(),
                bytes = bytes,
                errorMessage = null,
                usage = FoodImageBatchClient.Usage(inputTokens = 100, outputTokens = 4000),
            )

        beforeContainer { clearAll() }
        afterSpec { clearAll() }

        given("회수 — completed 배치") {
            `when`("PENDING_IMAGE 음식의 이미지가 완성돼 있으면") {
                then("스토리지 저장 → imageRef 갱신 → PENDING_REVIEW 전이 → item DONE → 배치 COLLECTED") {
                    val food = savePendingImage("갈비찜")
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_1")
                    fakeClient.results["file_1"] = listOf(okResult(food.id))

                    collectService.collectSubmitted()

                    val key = fakeStorage.heads.keys.single()
                    key shouldMatch FOOD_IMAGE_KEY_PATTERN
                    key shouldStartWith "images/food/b7b0c086d8e6_"
                    val reloaded = foodRepository.findById(food.id).get()
                    reloaded.imageRef shouldBe key
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    val item = itemRepository.findAll().single()
                    item.itemStatus shouldBe ImageBatchItemStatus.DONE
                    item.fileName shouldBe key
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.COLLECTED
                    batchRepository.findById(batch.id).get().collectedAt.shouldNotBeNull()
                }
            }

            `when`("텍스트 미완(INCOMPLETE) 음식의 이미지가 먼저 도착하면") {
                then("imageRef 만 저장하고 INCOMPLETE 를 유지한다") {
                    val food = foodRepository.save(Food.incomplete("이미지선도착"))
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_2")
                    fakeClient.results["file_2"] = listOf(okResult(food.id))

                    collectService.collectSubmitted()

                    val reloaded = foodRepository.findById(food.id).get()
                    reloaded.imageRef!! shouldMatch FOOD_IMAGE_KEY_PATTERN
                    reloaded.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                }
            }

            `when`("장당 결과에 usage 가 실려 있으면") {
                then("이미지 1장당 LlmCallCostIncurred 를 발행한다") {
                    val food1 = savePendingImage("비용음식1")
                    val food2 = savePendingImage("비용음식2")
                    val batch = saveSubmittedBatch(food1.id, food2.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_3")
                    fakeClient.results["file_3"] = listOf(okResult(food1.id), okResult(food2.id))

                    collectService.collectSubmitted()

                    costListener.events.size shouldBe 2
                    costListener.events.first().outputTokens shouldBe 4000
                }
            }

            `when`("항목별 error 가 섞여 있으면") {
                then("성공 항목은 DONE, 실패 항목만 FAILED(error_msg) 로 마감된다") {
                    val ok = savePendingImage("성공음식")
                    val bad = savePendingImage("실패음식")
                    val batch = saveSubmittedBatch(ok.id, bad.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_4")
                    fakeClient.results["file_4"] = listOf(
                        okResult(ok.id),
                        FoodImageBatchClient.Result(bad.id.toString(), null, "content policy violation", null),
                    )

                    collectService.collectSubmitted()

                    val itemsByFood = itemRepository.findAll().associateBy { it.foodId }
                    itemsByFood[ok.id]!!.itemStatus shouldBe ImageBatchItemStatus.DONE
                    itemsByFood[bad.id]!!.itemStatus shouldBe ImageBatchItemStatus.FAILED
                    itemsByFood[bad.id]!!.errorMsg shouldContain "content policy"
                    foodRepository.findById(bad.id).get().imageRef shouldBe null
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.COLLECTED
                }
            }

            `when`("결과 줄이 아예 없는 항목이 있으면(error file 로 빠진 실패분)") {
                then("FAILED 로 마감해 배치를 닫는다 — 다음 제출에 자동 재포함") {
                    val missing = savePendingImage("누락음식")
                    val batch = saveSubmittedBatch(missing.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_5")
                    fakeClient.results["file_5"] = emptyList()

                    collectService.collectSubmitted()

                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.FAILED
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.COLLECTED
                }
            }

            `when`("음식이 삭제된 뒤 결과가 도착하면") {
                then("항목만 실패 마감하고 건너뛴다") {
                    val food = savePendingImage("삭제음식")
                    val batch = saveSubmittedBatch(food.id)
                    foodRepository.save(food.apply { delete() })
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_6")
                    fakeClient.results["file_6"] = listOf(okResult(food.id))

                    collectService.collectSubmitted()

                    val item = itemRepository.findAll().single()
                    item.itemStatus shouldBe ImageBatchItemStatus.FAILED
                    item.errorMsg shouldContain "삭제"
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.COLLECTED
                }
            }
        }

        given("회수 — 결과 파일 다운로드 실패(HTTP 오류)") {
            `when`("결과 스트리밍이 예외를 던지면") {
                then("배치는 SUBMITTED 로 보존되고 항목은 PENDING 그대로 — 다음 틱에 재시도") {
                    val food = savePendingImage("다운로드실패음식")
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_broken")
                    fakeClient.failingFiles.add("file_broken")

                    collectService.collectSubmitted()

                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.SUBMITTED
                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.PENDING
                }
            }
        }

        given("회수 — SUBMITTING 잔류 복구(claim-first)") {
            `when`("제출 도중 중단돼 1시간 넘게 SUBMITTING 에 머문 배치가 있으면") {
                then("FAILED 로 마감하고 음식을 다음 제출 후보로 되돌린다") {
                    val food = savePendingImage("잔류음식")
                    val stale = batchRepository.save(
                        ImageBatch(
                            promptVersion = "v1",
                            model = "gpt-image-2",
                            submittedAt = java.time.LocalDateTime.now().minusHours(2),
                        ),
                    )
                    itemRepository.save(ImageBatchItem(batchId = stale.id, foodId = food.id))

                    collectService.collectSubmitted()

                    batchRepository.findById(stale.id).get().batchStatus shouldBe ImageBatchStatus.FAILED
                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.FAILED
                    foodRepository.findImageCandidates().map { it.id } shouldBe listOf(food.id)
                }
            }

            `when`("아직 리스(1시간) 안인 SUBMITTING 배치는") {
                then("건드리지 않는다 — 제출이 진행 중일 수 있다") {
                    val food = savePendingImage("제출중음식")
                    val fresh = batchRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                    itemRepository.save(ImageBatchItem(batchId = fresh.id, foodId = food.id))

                    collectService.collectSubmitted()

                    batchRepository.findById(fresh.id).get().batchStatus shouldBe ImageBatchStatus.SUBMITTING
                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.PENDING
                }
            }
        }

        given("회수 — 진행 중 배치") {
            `when`("배치가 아직 in_progress 이면") {
                then("건드리지 않고 다음 틱으로 넘긴다") {
                    val food = savePendingImage("진행중음식")
                    val batch = saveSubmittedBatch(food.id)

                    collectService.collectSubmitted()

                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.SUBMITTED
                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.PENDING
                    foodRepository.findById(food.id).get().imageRef shouldBe null
                }
            }
        }

        given("회수 — 멱등 재회수") {
            `when`("이미 DONE 인 항목이 섞인 배치를 다시 회수하면") {
                then("PENDING 항목만 처리하고 DONE 은 건너뛴다(비용 재발행 없음)") {
                    val done = savePendingImage("이미완료")
                    val pending = savePendingImage("미처리")
                    val batch = saveSubmittedBatch(done.id, pending.id)
                    itemRepository.findAll().first { it.foodId == done.id }
                        .also { itemRepository.save(it.apply { done("images/food/${done.id}.png") }) }
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_7")
                    fakeClient.results["file_7"] = listOf(okResult(done.id), okResult(pending.id))

                    collectService.collectSubmitted()

                    costListener.events.size shouldBe 1
                    itemRepository.findAll().all { it.itemStatus == ImageBatchItemStatus.DONE } shouldBe true
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.COLLECTED
                }
            }
        }

        given("저장 키 생성 규칙") {
            `when`("음식명으로 키를 만들면") {
                then("images/food/{sha256 앞 12자리}_{uuid 16자리}.png 형식이고 환경접두가 없다") {
                    val key = FoodImageBatchCollectService.storageKeyOf("불고기")
                    key shouldMatch FOOD_IMAGE_KEY_PATTERN
                    key shouldStartWith "images/food/0ac627c8cdea_"
                }
            }

            `when`("같은 음식명으로 두 번 만들면") {
                then("uuid 가 달라 서로 다른 키가 나온다") {
                    FoodImageBatchCollectService.storageKeyOf("불고기") shouldNotBe
                        FoodImageBatchCollectService.storageKeyOf("불고기")
                }
            }
        }

        given("회수 — 이미지 재생성") {
            `when`("이미지가 이미 있는 음식을 새 배치로 다시 회수하면") {
                then("이전 키를 덮어쓰지 않고 새 키로 저장하며 imageRef 가 새 키로 갱신된다") {
                    val food = savePendingImage("재생성음식")
                    val first = saveSubmittedBatch(food.id)
                    fakeClient.polls[first.openaiBatchId!!] = completed("file_regen_1")
                    fakeClient.results["file_regen_1"] = listOf(okResult(food.id))
                    collectService.collectSubmitted()
                    val firstKey = foodRepository.findById(food.id).get().imageRef!!

                    val second = saveSubmittedBatch(food.id)
                    fakeClient.polls[second.openaiBatchId!!] = completed("file_regen_2")
                    fakeClient.results["file_regen_2"] = listOf(okResult(food.id))
                    collectService.collectSubmitted()

                    val secondKey = foodRepository.findById(food.id).get().imageRef!!
                    secondKey shouldMatch FOOD_IMAGE_KEY_PATTERN
                    secondKey shouldNotBe firstKey
                    fakeStorage.heads.containsKey(firstKey) shouldBe true
                    fakeStorage.heads.containsKey(secondKey) shouldBe true
                }
            }
        }

        given("회수 — put 이후 트랜잭션 실패분 재시도") {
            `when`("PENDING 항목에 직전 시도가 예약해 둔 파일명이 남아 있으면") {
                then("새 키를 만들지 않고 예약 키를 재사용한다 — 재시도마다 고아 객체가 쌓이지 않는다") {
                    val food = savePendingImage("재시도음식")
                    val batch = saveSubmittedBatch(food.id)
                    val reservedKey = "images/food/aaaaaaaaaaaa_bbbbbbbbbbbbbbbb.png"
                    itemRepository.save(itemRepository.findAll().single().apply { fileName = reservedKey })
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_retry")
                    fakeClient.results["file_retry"] = listOf(okResult(food.id))

                    collectService.collectSubmitted()

                    fakeStorage.heads.keys.single() shouldBe reservedKey
                    foodRepository.findById(food.id).get().imageRef shouldBe reservedKey
                    val item = itemRepository.findAll().single()
                    item.itemStatus shouldBe ImageBatchItemStatus.DONE
                    item.fileName shouldBe reservedKey
                }
            }

            `when`("이미 예약된 항목에 다른 키로 클레임을 시도하면(중첩 회수 레이스)") {
                then("클레임이 거부되고 기존 예약 키가 유지된다 — 두 회수기가 한 키로 수렴") {
                    val food = savePendingImage("레이스음식")
                    saveSubmittedBatch(food.id)
                    val item = itemRepository.findAll().single()

                    itemRepository.reserveFileName(item.id, "images/food/aaaaaaaaaaaa_1111111111111111.png") shouldBe 1
                    itemRepository.reserveFileName(item.id, "images/food/aaaaaaaaaaaa_2222222222222222.png") shouldBe 0

                    itemRepository.findById(item.id).get().fileName shouldBe
                        "images/food/aaaaaaaaaaaa_1111111111111111.png"
                }
            }

            `when`("예약 파일명이 없는 항목을 정상 회수하면") {
                then("생성한 키가 put 전에 항목에 예약 저장되어 완료 키와 일치한다") {
                    val food = savePendingImage("예약음식")
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] = completed("file_reserve")
                    fakeClient.results["file_reserve"] = listOf(okResult(food.id))

                    collectService.collectSubmitted()

                    val item = itemRepository.findAll().single()
                    item.fileName shouldBe fakeStorage.heads.keys.single()
                }
            }
        }

        given("회수 — failed/expired 배치(US3)") {
            `when`("배치가 failed 로 끝나면") {
                then("PENDING 전 항목 FAILED + 배치 FAILED 로 마감된다") {
                    val food = savePendingImage("배치실패음식")
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] =
                        FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.FAILED, null, null)

                    collectService.collectSubmitted()

                    itemRepository.findAll().single().itemStatus shouldBe ImageBatchItemStatus.FAILED
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.FAILED
                }
            }

            `when`("expired 배치를 마감한 뒤 제출 후보를 다시 조회하면") {
                then("실패 음식이 자동 재포함된다 — 별도 재제출 로직 없음") {
                    val food = savePendingImage("만료음식")
                    val batch = saveSubmittedBatch(food.id)
                    fakeClient.polls[batch.openaiBatchId!!] =
                        FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.EXPIRED, null, null)

                    collectService.collectSubmitted()

                    itemRepository.findAll().single().errorMsg shouldContain "EXPIRED"
                    foodRepository.findImageCandidates().map { it.id } shouldBe listOf(food.id)
                }
            }

            `when`("expired 배치에 부분 완료 결과가 남아 있으면") {
                then("이미 과금된 완성분은 회수(DONE)하고 미처리분만 FAILED 로 푼다 — 배치 100건 손실 최소화") {
                    val done = savePendingImage("만료-완성분")
                    val lost = savePendingImage("만료-미처리분")
                    val batch = saveSubmittedBatch(done.id, lost.id)
                    fakeClient.polls[batch.openaiBatchId!!] =
                        FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.EXPIRED, "file_partial", null)
                    fakeClient.results["file_partial"] = listOf(okResult(done.id))

                    collectService.collectSubmitted()

                    val itemsByFood = itemRepository.findAll().associateBy { it.foodId }
                    itemsByFood[done.id]!!.itemStatus shouldBe ImageBatchItemStatus.DONE
                    foodRepository.findById(done.id).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    itemsByFood[lost.id]!!.itemStatus shouldBe ImageBatchItemStatus.FAILED
                    batchRepository.findById(batch.id).get().batchStatus shouldBe ImageBatchStatus.FAILED
                    foodRepository.findImageCandidates().map { it.id } shouldBe listOf(lost.id)
                }
            }
        }
    }
}
