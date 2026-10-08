package com.kbap.common.domain.food

import com.kbap.common.domain.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodViewLog
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
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var imageBatchRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var imageBatchItemRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var foodViewLogRepository: FoodViewLogJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

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

            `when`("READY 와 PENDING_REVIEW 가 섞여 있고 검색용 이름을 읽으면") {
                then("READY 음식만 돌려준다 — 한국어명·표시명·번역을 함께 싣는다") {
                    clear()
                    val readyId = saveReady("탐색-김치찌개")
                    savePendingReview("탐색-된장찌개")

                    val names = foodJpaRepository.findSearchableNames()

                    names.map { it.id } shouldBe listOf(readyId)
                    names.single().koreanName shouldBe "탐색-김치찌개"
                    names.single().nameTranslations shouldBe foodJpaRepository.findById(readyId).get().nameTranslations
                }
            }
        }

        given("findPopular — 최근 조회수 순 인기 음식") {
            fun since() = LocalDateTime.now().minusDays(30)

            fun clearAll() {
                clear()
                foodViewLogRepository.deleteAll()
            }

            fun view(foodId: Long, times: Int) =
                repeat(times) { foodViewLogRepository.save(FoodViewLog(foodId = foodId)) }

            fun popularIds(size: Int = 10) = foodJpaRepository.findPopular(since(), size).map { it.id }

            `when`("음식마다 조회수가 다르면") {
                then("조회수가 많은 순으로 반환한다") {
                    clearAll()
                    val once = saveReady("인기-한번")
                    val thrice = saveReady("인기-세번")
                    val twice = saveReady("인기-두번")
                    view(once, 1)
                    view(thrice, 3)
                    view(twice, 2)

                    popularIds() shouldBe listOf(thrice, twice, once)
                }
            }

            `when`("조회수가 같으면") {
                then("나중에 등록된 음식이 먼저 온다") {
                    clearAll()
                    val older = saveReady("동률-먼저")
                    val newer = saveReady("동률-나중")
                    view(older, 2)
                    view(newer, 2)

                    popularIds() shouldBe listOf(newer, older)
                }
            }

            `when`("공개되지 않은 음식에 조회 기록이 많으면") {
                then("결과에 포함하지 않는다") {
                    clearAll()
                    val ready = saveReady("공개-음식")
                    val pending = savePendingReview("검수대기-음식")
                    view(ready, 1)
                    view(pending, 5)

                    popularIds() shouldBe listOf(ready)
                }
            }

            `when`("조회 기록이 전부 기간 밖이면") {
                then("그 음식은 결과에 포함하지 않는다") {
                    clearAll()
                    val recent = saveReady("기간-안")
                    val stale = saveReady("기간-밖")
                    view(recent, 1)
                    view(stale, 3)
                    jdbcTemplate.update(
                        "UPDATE food_view_log SET created_at = ? WHERE food_id = ?",
                        LocalDateTime.now().minusDays(31),
                        stale,
                    )

                    popularIds() shouldBe listOf(recent)
                }
            }

            `when`("조회 기록이 있는 음식이 요청 크기보다 적으면") {
                then("조회 기록이 있는 음식만 반환한다") {
                    clearAll()
                    val viewed = saveReady("조회-있음1")
                    val viewedMore = saveReady("조회-있음2")
                    repeat(3) { saveReady("조회-없음$it") }
                    view(viewed, 1)
                    view(viewedMore, 2)

                    popularIds(size = 10) shouldBe listOf(viewedMore, viewed)
                }
            }

            `when`("조회 기록이 하나도 없으면") {
                then("빈 목록을 반환한다") {
                    clearAll()
                    saveReady("무조회-음식")

                    foodJpaRepository.findPopular(since(), 10).shouldBeEmpty()
                }
            }

            `when`("조회 기록이 있는 음식이 요청 크기보다 많으면") {
                then("상위 요청 크기만큼만 반환한다") {
                    clearAll()
                    val low = saveReady("상위-하")
                    val mid = saveReady("상위-중")
                    val high = saveReady("상위-상")
                    view(low, 1)
                    view(mid, 2)
                    view(high, 3)

                    popularIds(size = 2) shouldBe listOf(high, mid)
                }
            }
        }

        given("리뷰 많은 음식 조회") {
            fun reviewCount(foodId: Long, count: Int) =
                jdbcTemplate.update("UPDATE food SET review_count = ? WHERE id = ?", count, foodId)

            fun mostReviewedIds(size: Int = 10) = foodJpaRepository.findMostReviewed(size).map { it.id }

            `when`("음식마다 리뷰 수가 다르면") {
                then("리뷰 수 내림차순으로 반환하고 리뷰 0건 음식은 빠진다") {
                    clear()
                    val one = saveReady("리뷰-한건")
                    val three = saveReady("리뷰-세건")
                    saveReady("리뷰-없음")
                    reviewCount(one, 1)
                    reviewCount(three, 3)

                    mostReviewedIds() shouldBe listOf(three, one)
                }
            }

            `when`("리뷰 수가 같으면") {
                then("id 가 큰 음식이 먼저 온다") {
                    clear()
                    val older = saveReady("리뷰동률-먼저")
                    val newer = saveReady("리뷰동률-나중")
                    reviewCount(older, 2)
                    reviewCount(newer, 2)

                    mostReviewedIds() shouldBe listOf(newer, older)
                }
            }

            `when`("공개되지 않은 음식의 리뷰 수가 크면") {
                then("결과에 포함하지 않는다") {
                    clear()
                    val ready = saveReady("리뷰-공개")
                    val pendingImage = savePendingImage("리뷰-이미지대기")
                    val failed = saveFailed("리뷰-실패")
                    reviewCount(ready, 1)
                    reviewCount(pendingImage, 5)
                    reviewCount(failed, 5)

                    mostReviewedIds() shouldBe listOf(ready)
                }
            }

            `when`("소프트 삭제된 음식의 리뷰 수가 크면") {
                then("결과에 포함하지 않는다") {
                    clear()
                    val alive = saveReady("리뷰-생존")
                    val ghostId = saveReady("리뷰-삭제")
                    reviewCount(alive, 1)
                    reviewCount(ghostId, 5)
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)

                    mostReviewedIds() shouldBe listOf(alive)
                }
            }

            `when`("리뷰 있는 음식이 요청 크기보다 많으면") {
                then("상위 요청 크기만큼만 반환한다") {
                    clear()
                    val low = saveReady("리뷰상위-하")
                    val mid = saveReady("리뷰상위-중")
                    val high = saveReady("리뷰상위-상")
                    reviewCount(low, 1)
                    reviewCount(mid, 2)
                    reviewCount(high, 3)

                    mostReviewedIds(size = 2) shouldBe listOf(high, mid)
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
