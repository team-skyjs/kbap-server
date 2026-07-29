package com.kbap.common.domain.food

import com.kbap.common.domain.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest

@SpringBootTest(classes = [FoodTestApp::class])
@Import(MySqlContainerConfig::class)
class FoodJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var imageBatchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var imageBatchItemRepository: ImageBatchItemJpaRepository

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

        fun savePendingReview(koreanName: String): Long =
            foodJpaRepository.save(
                Food(
                    koreanName = koreanName,
                    description = "구수한 $koreanName",
                    contentStatus = FoodContentStatus.PENDING_REVIEW,
                ),
            ).id

        fun findIncomplete(afterId: Long?, size: Int): List<Food> =
            foodJpaRepository.findIncompleteAfter(afterId, PageRequest.of(0, size))

        given("findIncompleteAfter — INCOMPLETE 만 id 오름차순 청크") {
            `when`("INCOMPLETE 5건에서 afterId=null, size=3 으로 조회하면") {
                then("가장 작은 id 3건을 오름차순으로 반환한다") {
                    clear()
                    val ids = (1..5).map { saveIncomplete("청크-음식$it") }.sorted()

                    val chunk = findIncomplete(afterId = null, size = 3)

                    chunk.map { it.id } shouldBe ids.take(3)
                }
            }
        }

        given("findIncompleteAfter — 키셋(afterId 이후)") {
            `when`("afterId 로 첫 청크의 마지막 id 를 주면") {
                then("그 id 이후만 반환한다(중복·건너뜀 없음)") {
                    clear()
                    val ids = (1..5).map { saveIncomplete("키셋-음식$it") }.sorted()

                    val first = findIncomplete(afterId = null, size = 2)
                    val second = findIncomplete(afterId = first.last().id, size = 2)

                    first.map { it.id } shouldBe ids.take(2)
                    second.map { it.id } shouldBe ids.drop(2).take(2)
                }
            }
        }

        given("findIncompleteAfter — READY 제외") {
            `when`("READY 와 INCOMPLETE 가 섞여 있으면") {
                then("INCOMPLETE 만 반환한다") {
                    clear()
                    saveReady("완성-김치찌개")
                    val incompleteId = saveIncomplete("미완성-된장찌개")

                    val chunk = findIncomplete(afterId = null, size = 10)

                    chunk.map { it.id } shouldBe listOf(incompleteId)
                }
            }

            `when`("INCOMPLETE 가 하나도 없으면") {
                then("빈 목록을 반환한다") {
                    clear()
                    saveReady("완성-순두부")

                    findIncomplete(afterId = null, size = 10).shouldBeEmpty()
                }
            }
        }

        given("검수 대기 전이 저장 — 완비 시 저장 + 전이") {
            `when`("스텝이 사진·설명·번역을 채우고 기피성분 매핑이 있으면") {
                then("채운 필드를 저장하고 PENDING_REVIEW 로 전이하며 true 를 반환한다") {
                    clear()
                    val id = saveIncomplete("완비-부대찌개")
                    val food = findIncomplete(afterId = null, size = 1).single()
                    food.imageRef = "s3://img/budae.jpg"
                    food.description = "얼큰한 부대찌개"
                    food.spiciness = 5
                    food.nameTranslations = targets
                    food.descriptionTranslations = targets
                    food.avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100))

                    val transitioned = food.transitionByContentState()
                    foodJpaRepository.save(food)

                    transitioned shouldBe FoodContentStatus.PENDING_REVIEW
                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    reloaded.imageRef shouldBe "s3://img/budae.jpg"
                    reloaded.description shouldBe "얼큰한 부대찌개"
                }
            }
        }

        given("검수 대기 전이 저장 — 미완비 시 저장만") {
            `when`("사진만 채우고 번역·매핑이 없으면") {
                then("채운 필드는 저장하되 INCOMPLETE 를 유지하고 false 를 반환한다") {
                    clear()
                    val id = saveIncomplete("미완비-청국장")
                    val food = findIncomplete(afterId = null, size = 1).single()
                    food.imageRef = "s3://img/cheonggukjang.jpg"

                    val transitioned = food.transitionByContentState()
                    foodJpaRepository.save(food)

                    transitioned shouldBe FoodContentStatus.INCOMPLETE
                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.contentStatus shouldBe FoodContentStatus.INCOMPLETE
                    reloaded.imageRef shouldBe "s3://img/cheonggukjang.jpg"
                }
            }
        }

        given("findImageCandidates — 이미지 제출 후보(상태 무관, imageRef 부재 + 진행 중 배치 미포함)") {
            `when`("INCOMPLETE·PENDING_IMAGE 각각 이미지 없는 음식과, 이미지 있는 READY 음식이 섞여 있으면") {
                then("이미지 없는 음식만 상태와 무관하게 후보로 반환한다") {
                    clear()
                    imageBatchItemRepository.deleteAll()
                    val incompleteId = saveIncomplete("후보-마라탕")
                    val pendingImageId = foodJpaRepository.save(
                        Food.incomplete("후보-쌀국수").apply { contentStatus = FoodContentStatus.PENDING_IMAGE },
                    ).id
                    foodJpaRepository.save(
                        Food(
                            koreanName = "완성-비빔밥",
                            description = "이미지 보유",
                            imageRef = "images/food/99.png",
                            contentStatus = FoodContentStatus.READY,
                        ),
                    )

                    val candidates = foodJpaRepository.findImageCandidates()

                    candidates.map { it.id }.sorted() shouldBe listOf(incompleteId, pendingImageId).sorted()
                }
            }

            `when`("진행 중 배치(PENDING item)에 이미 포함된 음식이 있으면") {
                then("그 음식은 후보에서 빠진다 — 버튼 연타 중복 제출 가드") {
                    clear()
                    imageBatchItemRepository.deleteAll()
                    imageBatchRepository.deleteAll()
                    val pendingFoodId = saveIncomplete("진행중-김치찌개")
                    val freshFoodId = saveIncomplete("미제출-된장찌개")
                    val batchId = imageBatchRepository.save(
                        ImageBatch(openaiBatchId = "batch_x", promptVersion = "v1", model = "gpt-image-2"),
                    ).id
                    imageBatchItemRepository.save(ImageBatchItem(batchId = batchId, foodId = pendingFoodId))

                    val candidates = foodJpaRepository.findImageCandidates()

                    candidates.map { it.id } shouldBe listOf(freshFoodId)
                }
            }

            `when`("이전 배치에서 FAILED 로 마감된 음식이면") {
                then("PENDING 이 아니므로 다음 제출 후보에 자동 재포함된다") {
                    clear()
                    imageBatchItemRepository.deleteAll()
                    imageBatchRepository.deleteAll()
                    val failedFoodId = saveIncomplete("실패-갈비탕")
                    val batchId = imageBatchRepository.save(
                        ImageBatch(openaiBatchId = "batch_y", promptVersion = "v1", model = "gpt-image-2"),
                    ).id
                    imageBatchItemRepository.save(
                        ImageBatchItem(batchId = batchId, foodId = failedFoodId).apply { fail("expired") },
                    )

                    foodJpaRepository.findImageCandidates().map { it.id } shouldBe listOf(failedFoodId)
                }
            }
        }

        given("낙관적 락(@Version) — 배치·회수 병행 갱신의 lost update 검출") {
            `when`("같은 음식을 두 번 조회해 각각 수정 후 순서대로 저장하면") {
                then("먼저 저장한 쪽만 성공하고 뒤(구버전)는 버전 충돌로 거부된다") {
                    clear()
                    val id = saveIncomplete("버전충돌-김치찜")
                    val copy1 = foodJpaRepository.findById(id).get()
                    val copy2 = foodJpaRepository.findById(id).get()

                    copy1.imageRef = "images/food/$id.png"
                    foodJpaRepository.saveAndFlush(copy1)

                    copy2.description = "구버전 스냅샷의 설명"
                    val stale = runCatching { foodJpaRepository.saveAndFlush(copy2) }

                    stale.isFailure shouldBe true
                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.imageRef shouldBe "images/food/$id.png"
                }
            }
        }

        given("사용자 노출 조회 — READY 만 노출, PENDING_REVIEW 비노출") {
            `when`("READY 와 PENDING_REVIEW 가 섞여 있고 목록 페이지를 조회하면") {
                then("READY 음식 id 만 반환한다") {
                    clear()
                    val readyId = saveReady("완성-김치찌개")
                    savePendingReview("검수대기-된장찌개")

                    val ids = foodJpaRepository.findFoodPageIds(cursor = null, PageRequest.of(0, 10))

                    ids shouldBe listOf(readyId)
                }
            }

            `when`("READY 와 PENDING_REVIEW 가 섞여 있고 이름으로 검색하면") {
                then("READY 음식 id 만 반환한다") {
                    clear()
                    val readyId = saveReady("탐색-김치찌개")
                    savePendingReview("탐색-된장찌개")

                    val ids = foodJpaRepository.searchFoodPageIds(
                        keyword = "탐색",
                        jsonPath = null,
                        cursor = null,
                        size = 10,
                    )

                    ids shouldBe listOf(readyId)
                }
            }

            `when`("PENDING_REVIEW 만 있고 랜덤 조회하면") {
                then("빈 목록을 반환한다") {
                    clear()
                    savePendingReview("랜덤-검수대기")

                    foodJpaRepository.findRandomReadyIds(size = 10).shouldBeEmpty()
                }
            }
        }

        given("markPendingImageByIdIn / markPendingReviewByIdIn — 가드 있는 상태 벌크 전환") {
            `when`("INCOMPLETE 3건 중 2건만 PENDING_REVIEW 로 벌크 전환하면") {
                then("지정한 id 만 전환되고 나머지는 그대로다") {
                    clear()
                    val target1 = saveIncomplete("벌크-김치찌개")
                    val target2 = saveIncomplete("벌크-된장찌개")
                    val untouched = saveIncomplete("벌크-갈비탕")

                    val updated = foodJpaRepository.markPendingReviewByIdIn(listOf(target1, target2))

                    updated shouldBe 2
                    foodJpaRepository.findById(target1).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    foodJpaRepository.findById(target2).get().contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    foodJpaRepository.findById(untouched).get().contentStatus shouldBe FoodContentStatus.INCOMPLETE
                }
            }

            `when`("이미지 없는 INCOMPLETE 음식을 PENDING_IMAGE 로 벌크 전환하면") {
                then("전환되고 version 이 올라 병행 세션의 stale save 가 무효화된다") {
                    clear()
                    val id = saveIncomplete("벌크-쌀국수")
                    val before = foodJpaRepository.findById(id).get().version

                    foodJpaRepository.markPendingImageByIdIn(listOf(id)) shouldBe 1

                    val reloaded = foodJpaRepository.findById(id).get()
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    reloaded.version shouldBe before + 1
                }
            }

            `when`("그 사이 이미지가 도착한(imageRef 보유) 음식을 PENDING_IMAGE 로 전환하려 하면") {
                then("가드에 걸려 갱신 0건 — 낡은 스냅샷이 상태를 후퇴시키지 않는다") {
                    clear()
                    val id = saveIncomplete("벌크-이미지선도착")
                    val food = foodJpaRepository.findById(id).get()
                    food.imageRef = "images/food/$id.png"
                    foodJpaRepository.saveAndFlush(food)

                    foodJpaRepository.markPendingImageByIdIn(listOf(id)) shouldBe 0
                    foodJpaRepository.findById(id).get().contentStatus shouldBe FoodContentStatus.INCOMPLETE
                }
            }

            `when`("빈 id 목록으로 호출하면") {
                then("아무것도 갱신하지 않고 0 을 반환한다") {
                    clear()
                    saveIncomplete("벌크-빈목록")

                    foodJpaRepository.markPendingReviewByIdIn(emptyList()) shouldBe 0
                }
            }
        }

        given("countGroupByContentStatus — 상태별 건수 집계") {
            `when`("여러 상태의 음식이 섞여 있으면") {
                then("존재하는 상태만 건수와 함께 반환한다") {
                    clear()
                    saveIncomplete("집계-미완료1")
                    saveIncomplete("집계-미완료2")
                    saveReady("집계-레디1")
                    savePendingReview("집계-검수1")

                    val counts = foodJpaRepository.countGroupByContentStatus()
                        .associate { it.status to it.count }

                    counts shouldBe mapOf(
                        FoodContentStatus.INCOMPLETE to 2L,
                        FoodContentStatus.READY to 1L,
                        FoodContentStatus.PENDING_REVIEW to 1L,
                    )
                }
            }

            `when`("음식이 한 건도 없으면") {
                then("빈 목록을 반환한다") {
                    clear()

                    foodJpaRepository.countGroupByContentStatus() shouldBe emptyList()
                }
            }

            `when`("소프트 삭제된 음식이 있으면") {
                then("집계에서 제외된다") {
                    clear()
                    val ghostId = saveIncomplete("집계-유령")
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)
                    saveReady("집계-생존")

                    val counts = foodJpaRepository.countGroupByContentStatus()
                        .associate { it.status to it.count }

                    counts shouldBe mapOf(FoodContentStatus.READY to 1L)
                }
            }
        }
    }
}
