package com.kbap.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.allTargets
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.failedBody
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.passedBody
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.RegenerationIntent
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post

@IntegrationTest
class AdminFoodContentIngestControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var imageBatchJpaRepository: ImageBatchJpaRepository

    @Autowired
    private lateinit var imageBatchItemJpaRepository: ImageBatchItemJpaRepository

    @Autowired
    private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val namePrefix = "적재테스트-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus, imageRef: String?): Food {
            val food = foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    imageRef = imageRef,
                    description = Food.PLACEHOLDER_DESCRIPTION,
                    spiciness = Food.SPICINESS_UNASSESSED,
                    contentStatus = contentStatus,
                    ingredients = null,
                ),
            )
            outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
            return food
        }

        fun ingest(body: Map<String, Any?>): ResultActionsDsl {
            val foodId = body.getValue("foodId") as Long
            val outboxId = body["outboxId"] ?: outboxRepository
                .findByFoodIdInAndOutboxStatus(setOf(foodId), FoodContentOutboxStatus.PENDING)
                .singleOrNull()
                ?.id
                ?: foodJpaRepository.findById(foodId).orElse(null)?.let {
                    outboxRepository.save(FoodContentOutbox.pending(it.id, it.displayName)).id
                }
                ?: 999_999L
            return mockMvc.post(PATH) {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body + ("outboxId" to outboxId))
            }
        }

        fun reloaded(id: Long): Food = foodJpaRepository.findById(id).orElseThrow()

        given("포기한 요청의 결과 적재") {
            `when`("dead_at 이 찍힌 요청의 콜백이 뒤늦게 도착하면") {
                then("200 으로 받되 내용은 반영하지 않는다 — 막기만 하면 컨슈머가 영원히 재시도한다") {
                    clearFoods()
                    val food = saveFood("포기요청음식", FoodContentStatus.FAILED, null)
                    val outbox = outboxRepository.findByFoodIdInAndOutboxStatus(
                        setOf(food.id),
                        FoodContentOutboxStatus.PENDING,
                    ).single()
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "UPDATE food_content_outbox SET outbox_status = 'SENT', sent_at = NOW(6), " +
                                    "dead_at = NOW(6), last_error = '테스트 포기' WHERE id = ${outbox.id}",
                            )
                        }
                    }

                    ingest(passedBody(food.id, outbox.id)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    val reloaded = reloaded(food.id)
                    reloaded.description shouldBe Food.PLACEHOLDER_DESCRIPTION
                    reloaded.contentStatus shouldBe FoodContentStatus.FAILED
                }
            }
        }

        given("재수집으로 대체된 옛 요청의 결과 적재") {
            `when`("새 요청의 결과가 반영된 뒤 옛 요청의 결과가 늦게 도착하면") {
                then("200 으로 받되 옛 내용으로 덮어쓰지 않고, 옛 요청 행도 그대로 둔다") {
                    clearFoods()
                    val food = saveFood("재수집경합음식", FoodContentStatus.FAILED, "images/webp/food/race.webp")
                    val old = outboxRepository.findByFoodIdInAndOutboxStatus(
                        setOf(food.id),
                        FoodContentOutboxStatus.PENDING,
                    ).single()
                    val newer = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "UPDATE food_content_outbox SET outbox_status = 'SENT', sent_at = NOW(6) " +
                                    "WHERE id IN (${old.id}, ${newer.id})",
                            )
                        }
                    }

                    ingest(passedBody(food.id, newer.id, description = "새 요청이 만든 설명")).andExpect { status { isOk() } }
                    ingest(passedBody(food.id, old.id, description = "옛 요청이 만든 설명")).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    reloaded(food.id).description shouldBe "새 요청이 만든 설명"
                    outboxRepository.findById(old.id).orElseThrow().outboxStatus shouldBe FoodContentOutboxStatus.SENT
                }
            }

            `when`("새 요청이 아직 응답 전인데 옛 요청의 결과가 먼저 도착하면") {
                then("옛 결과는 반영하지 않는다 — 음식에 적용될 결과는 최신 요청의 것뿐이다") {
                    clearFoods()
                    val food = saveFood("재수집선착음식", FoodContentStatus.FAILED, null)
                    val old = outboxRepository.findByFoodIdInAndOutboxStatus(
                        setOf(food.id),
                        FoodContentOutboxStatus.PENDING,
                    ).single()
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute("UPDATE food_content_outbox SET outbox_status = 'SENT', sent_at = NOW(6) WHERE id = ${old.id}")
                        }
                    }
                    outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    ingest(passedBody(food.id, old.id, description = "옛 요청이 만든 설명")).andExpect { status { isOk() } }

                    reloaded(food.id).description shouldBe Food.PLACEHOLDER_DESCRIPTION
                }
            }
        }

        given("READY 음식 재수집 결과의 벡터 재동기화") {
            `when`("READY 음식에 재수집 결과가 반영되면") {
                then("READY 그대로이고 벡터 UPSERT 가 예약된다 — 내용이 바뀌었는데 벡터가 낡지 않게") {
                    clearFoods()
                    val food = saveFood("벡터재동기음식", FoodContentStatus.READY, "images/webp/food/v.webp")

                    ingest(passedBody(food.id, longDescription = "재수집으로 바뀐 긴 설명")).andExpect { status { isOk() } }

                    reloaded(food.id).contentStatus shouldBe FoodContentStatus.READY
                    vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING) shouldBe true
                }
            }

            `when`("이미 PENDING UPSERT 가 있는 READY 음식에 재수집 결과가 반영되면") {
                then("억제하지 않고 UPSERT 행을 하나 더 만든다 — 기존 행을 배치가 이미 읽어 옛 내용으로 임베딩 중일 수 있다") {
                    clearFoods()
                    val food = saveFood("벡터중복큐음식", FoodContentStatus.READY, "images/webp/food/dup.webp")
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    vectorOutboxRepository.findByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING).size shouldBe 2
                }
            }

            `when`("READY 가 아닌 음식에 재수집 결과가 반영되면") {
                then("벡터 UPSERT 를 예약하지 않는다 — 승인 때 예약된다") {
                    clearFoods()
                    val food = saveFood("비공개재수집음식", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    reloaded(food.id).contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    vectorOutboxRepository.existsByFoodIdAndOperationAndOutboxStatus(food.id, FoodVectorOutboxOperation.UPSERT, FoodVectorOutboxStatus.PENDING) shouldBe false
                }
            }
        }

        given("이미지 재생성 중 도착한 재수집 결과") {
            `when`("가드 이전에 만들어진 요청의 결과가 재생성 진행 중에 도착하면") {
                then("내용은 반영하되 content_status 는 그대로다 — 상태는 재생성 경로가 소유한다") {
                    clearFoods()
                    val food = saveFood("재생성중적재음식", FoodContentStatus.PENDING_IMAGE, "images/webp/food/old.webp")
                    val batch = imageBatchJpaRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                    imageBatchItemJpaRepository.save(ImageBatchItem(batchId = batch.id, foodId = food.id, regenerationIntent = RegenerationIntent.WRONG_IMAGE))

                    ingest(passedBody(food.id, description = "재생성 중에 온 설명")).andExpect { status { isOk() } }

                    val reloaded = reloaded(food.id)
                    reloaded.description shouldBe "재생성 중에 온 설명"
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }
        }

        given("이미지 재생성 중 도착한 실패 콜백") {
            `when`("재생성 진행 중에 실패 결과가 도착하면") {
                then("실패 사유는 남기되 content_status 는 그대로다 — FAILED 로 바꾸면 뒤에 오는 이미지가 거절된다") {
                    clearFoods()
                    val food = saveFood("재생성중실패음식", FoodContentStatus.PENDING_IMAGE, "images/webp/food/old.webp")
                    val batch = imageBatchJpaRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                    imageBatchItemJpaRepository.save(ImageBatchItem(batchId = batch.id, foodId = food.id, regenerationIntent = RegenerationIntent.REPLACE_BETTER))

                    ingest(failedBody(food.id, reason = "재생성 중 실패")).andExpect { status { isOk() } }

                    val reloaded = reloaded(food.id)
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    reloaded.contentReviewRejectionReason shouldBe "재생성 중 실패"
                }
            }
        }

        given("잠금 대기 중 시작된 재생성과 실패 콜백") {
            `when`("다른 트랜잭션이 음식 행을 잠근 채 재생성 항목을 넣는 동안 실패 콜백이 오면") {
                then("콜백은 그 트랜잭션 뒤로 직렬화돼(대기) 커밋된 재생성을 보고 content_status 를 유지한다") {
                    clearFoods()
                    val food = saveFood("잠금경합실패음식", FoodContentStatus.PENDING_IMAGE, "images/webp/food/old.webp")
                    val locked = java.util.concurrent.CountDownLatch(1)
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val regen = executor.submit {
                        org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                            foodJpaRepository.findByIdForUpdate(food.id)
                            val batch = imageBatchJpaRepository.save(ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                            imageBatchItemJpaRepository.saveAndFlush(ImageBatchItem(batchId = batch.id, foodId = food.id, regenerationIntent = RegenerationIntent.WRONG_IMAGE))
                            locked.countDown()
                            Thread.sleep(2_000)
                        }
                    }
                    locked.await()

                    val started = System.nanoTime()
                    ingest(failedBody(food.id, reason = "잠금 경합 실패")).andExpect { status { isOk() } }
                    java.time.Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThan 1_000L
                    regen.get()
                    executor.shutdown()

                    reloaded(food.id).contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }
        }

        given("성공 결과 적재") {
            `when`("이미 서비스 중이고 사진이 있는 음식이면") {
                then("텍스트만 갱신되고 상태·사진은 그대로다") {
                    clearFoods()
                    val food = saveFood("칼국수", FoodContentStatus.READY, "images/food/kalguksu.webp")

                    ingest(passedBody(food.id)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    val updated = reloaded(food.id)
                    updated.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                    updated.spiciness shouldBe 2
                    updated.nameTranslations shouldBe allTargets("칼국수")
                    updated.ingredients?.map { it.code } shouldBe listOf("SESAME")
                    updated.contentStatus shouldBe FoodContentStatus.READY
                    updated.imageRef shouldBe "images/food/kalguksu.webp"
                }
            }

            `when`("관리자 확인 대상이고 사진이 이미 있으면") {
                then("승인 대기로 가고 사진은 재활용된다") {
                    clearFoods()
                    val food = saveFood("콩국수", FoodContentStatus.FAILED, "images/food/kongguksu.webp")

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    updated.imageRef shouldBe "images/food/kongguksu.webp"
                }
            }

            `when`("사진이 없는 음식이면") {
                then("이미지 생성 대기가 된다") {
                    clearFoods()
                    val food = saveFood("잔치국수", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    reloaded(food.id).contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("벡터 메타데이터용 긴 설명이 함께 오면") {
                then("긴 설명이 저장된다") {
                    clearFoods()
                    val food = saveFood("들깨수제비", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id, longDescription = "들깨" .repeat(400))).andExpect { status { isOk() } }

                    reloaded(food.id).longDescription shouldBe "들깨".repeat(400)
                }
            }

            `when`("긴 설명 없이 재적재되면") {
                then("이전 긴 설명은 지워진다") {
                    clearFoods()
                    val food = saveFood("김치수제비", FoodContentStatus.FAILED, null)
                    ingest(passedBody(food.id, longDescription = "긴 설명")).andExpect { status { isOk() } }

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    reloaded(food.id).longDescription.shouldBeNull()
                }
            }

            `when`("직전 실패 기록이 있는 음식이면") {
                then("실패 유형과 사유가 지워진다") {
                    clearFoods()
                    val food = saveFood("비빔국수", FoodContentStatus.FAILED, null)
                    ingest(AdminFoodContentIngestTestSupport.failedBody(food.id)).andExpect { status { isOk() } }

                    ingest(passedBody(food.id)).andExpect { status { isOk() } }

                    val updated = reloaded(food.id)
                    updated.contentFailureKind.shouldBeNull()
                    updated.contentReviewRejectionReason.shouldBeNull()
                }
            }

            `when`("같은 결과가 두 번 도착하면") {
                then("두 번째 요청은 이미 처리된 계약 응답으로 거절한다") {
                    clearFoods()
                    val food = saveFood("메밀국수", FoodContentStatus.FAILED, null)
                    val outbox = outboxRepository
                        .findByFoodIdInAndOutboxStatus(setOf(food.id), FoodContentOutboxStatus.PENDING)
                        .single()

                    ingest(passedBody(food.id, outbox.id)).andExpect { status { isOk() } }
                    ingest(
                        passedBody(
                            food.id,
                            outbox.id,
                            description = "중복 요청이 덮어쓰면 안 되는 설명",
                        ),
                    ).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-004") }
                    }

                    val updated = reloaded(food.id)
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
                    updated.description shouldBe "들깨를 곱게 갈아 넣어 고소한 칼국수"
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.COMPLETE
                }
            }

            `when`("재료가 빈 배열이면") {
                then("조사 완료·해당 없음으로 저장한다") {
                    clearFoods()
                    val food = saveFood("우동", FoodContentStatus.FAILED, null)

                    ingest(passedBody(food.id, ingredients = emptyList())).andExpect { status { isOk() } }

                    reloaded(food.id).ingredients shouldBe emptyList()
                }
            }
        }
    }
}
