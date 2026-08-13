package com.kbap.common.domain.food

import com.kbap.common.domain.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
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

        fun saveFailed(koreanName: String): Long =
            foodJpaRepository.save(Food.failed(koreanName)).id

        fun savePendingImage(koreanName: String): Long =
            foodJpaRepository.save(
                Food(
                    koreanName = koreanName,
                    description = "구수한 $koreanName",
                    contentStatus = FoodContentStatus.PENDING_IMAGE,
                ),
            ).id

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

        given("findImageCandidates — 이미지 제출 후보(PENDING_IMAGE + 진행 중 배치 미포함)") {
            `when`("PENDING_IMAGE 와 FAILED·READY 가 섞여 있으면") {
                then("PENDING_IMAGE 만 후보로 반환한다 — FAILED 는 관리자 확인 대상이라 이미지 생성에서 제외된다") {
                    clear()
                    imageBatchItemRepository.deleteAll()
                    saveFailed("후보-마라탕")
                    val pendingImageId = savePendingImage("후보-쌀국수")
                    foodJpaRepository.save(
                        Food(
                            koreanName = "완성-비빔밥",
                            description = "이미지 보유",
                            imageRef = "images/food/99.png",
                            contentStatus = FoodContentStatus.READY,
                        ),
                    )

                    val candidates = foodJpaRepository.findImageCandidates()

                    candidates.map { it.id } shouldBe listOf(pendingImageId)
                }
            }

            `when`("진행 중 배치(PENDING item)에 이미 포함된 음식이 있으면") {
                then("그 음식은 후보에서 빠진다 — 버튼 연타 중복 제출 가드") {
                    clear()
                    imageBatchItemRepository.deleteAll()
                    imageBatchRepository.deleteAll()
                    val pendingFoodId = savePendingImage("진행중-김치찌개")
                    val freshFoodId = savePendingImage("미제출-된장찌개")
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
                    val failedFoodId = savePendingImage("실패-갈비탕")
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
                    val id = saveFailed("버전충돌-김치찜")
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

        given("countGroupByContentStatus — 상태별 건수 집계") {
            `when`("여러 상태의 음식이 섞여 있으면") {
                then("존재하는 상태만 건수와 함께 반환한다") {
                    clear()
                    saveFailed("집계-미완료1")
                    saveFailed("집계-미완료2")
                    saveReady("집계-레디1")
                    savePendingReview("집계-검수1")
                    foodJpaRepository.save(
                        Food(
                            koreanName = "집계-이미지대기1",
                            description = "구수한 집계-이미지대기1",
                            contentStatus = FoodContentStatus.PENDING_IMAGE,
                        ),
                    )

                    val counts = foodJpaRepository.countGroupByContentStatus()
                        .associate { it.status to it.count }

                    counts shouldBe mapOf(
                        FoodContentStatus.FAILED to 2L,
                        FoodContentStatus.READY to 1L,
                        FoodContentStatus.PENDING_REVIEW to 1L,
                        FoodContentStatus.PENDING_IMAGE to 1L,
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
                    val ghostId = saveFailed("집계-유령")
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

        given("upsertIncomplete — 매칭용 이름과 표시명 분리 저장") {
            `when`("match key 와 원본 표기로 적재하면") {
                then("두 이름을 각각 저장한다") {
                    clear()

                    foodJpaRepository.upsertIncomplete(listOf(Food.failed("들깨칼국수", "들깨 칼국수")))

                    val saved = foodJpaRepository.findByKoreanNameIn(setOf("들깨칼국수")).single()
                    saved.koreanName shouldBe "들깨칼국수"
                    saved.displayName shouldBe "들깨 칼국수"
                }
            }

            `when`("같은 match key 를 다른 표기로 다시 적재하면") {
                then("신규 행 없이 먼저 저장된 표시명을 유지한다") {
                    clear()
                    foodJpaRepository.upsertIncomplete(listOf(Food.failed("들깨칼국수", "들깨 칼국수")))

                    foodJpaRepository.upsertIncomplete(listOf(Food.failed("들깨칼국수", "들깨칼국수")))

                    val rows = foodJpaRepository.findByKoreanNameIn(setOf("들깨칼국수"))
                    rows.size shouldBe 1
                    rows.single().displayName shouldBe "들깨 칼국수"
                }
            }

            `when`("표시명이 비어 있는 기존 행에 다시 적재하면") {
                then("빈 표시명을 새 표기로 채운다(백필 누락·구버전 쓰기 자가 치유)") {
                    clear()
                    val blank = foodJpaRepository.save(Food.failed("순두부찌개"))
                    blank.displayName = ""
                    foodJpaRepository.save(blank)

                    foodJpaRepository.upsertIncomplete(listOf(Food.failed("순두부찌개", "순두부 찌개")))

                    val rows = foodJpaRepository.findByKoreanNameIn(setOf("순두부찌개"))
                    rows.size shouldBe 1
                    rows.single().displayName shouldBe "순두부 찌개"
                }
            }
        }
    }
}
