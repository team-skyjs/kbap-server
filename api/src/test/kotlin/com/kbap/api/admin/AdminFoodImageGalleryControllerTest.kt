package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodImage
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class AdminFoodImageGalleryControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var foodImageRepository: FoodImageJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: com.kbap.common.domain.food.FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var adminFoodImageService: AdminFoodImageService

    @Autowired
    private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @Autowired
    private lateinit var imageBatchJpaRepository: com.kbap.common.domain.food.ImageBatchJpaRepository

    @Autowired
    private lateinit var imageBatchItemJpaRepository: com.kbap.common.domain.food.ImageBatchItemJpaRepository

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun clear(): Unit = com.kbap.api.TestTables.clearAll(dataSource)

        beforeContainer { clear() }
        afterSpec { clear() }

        fun adminToken(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun saveFood(name: String, imageRef: String?, status: FoodContentStatus = FoodContentStatus.READY): Food =
            foodRepository.save(
                Food(koreanName = name, description = "설명", imageRef = imageRef, contentStatus = status),
            )

        fun saveImage(foodId: Long, key: String, isPrimary: Boolean, sortOrder: Int): FoodImage =
            foodImageRepository.save(
                FoodImage(foodId = foodId, imageKey = key, isPrimary = isPrimary, sortOrder = sortOrder),
            )

        fun gallery(foodId: Long, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.get("/api/admin/foods/$foodId/images") {
                token?.let { header("Authorization", "Bearer $it") }
            }

        fun setPrimary(foodId: Long, imageId: Long, version: Long, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.put("/api/admin/foods/$foodId/images/$imageId/primary") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("version" to version))
            }

        fun payloadOf(result: ResultActionsDsl) =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun regenerate(foodId: Long, intent: String?, reason: String? = null): ResultActionsDsl =
            mockMvc.post("/api/admin/foods/$foodId/regenerate-image") {
                header("Authorization", "Bearer ${adminToken()}")
                if (intent != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("intent" to intent, "reason" to reason))
                }
            }

        fun failLastItem(foodId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE image_batch_item SET item_status = 'FAILED', error_msg = '테스트 실패' WHERE food_id = ? " +
                        "ORDER BY id DESC LIMIT 1",
                ).use { ps -> ps.setLong(1, foodId); ps.executeUpdate() }
            }

        fun finishLastItem(foodId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE image_batch_item SET item_status = 'DONE', file_name = 'images/webp/food/new.webp' WHERE food_id = ? " +
                        "ORDER BY id DESC LIMIT 1",
                ).use { ps -> ps.setLong(1, foodId); ps.executeUpdate() }
            }

        fun detail(foodId: Long): ResultActionsDsl =
            mockMvc.get("/api/admin/foods/$foodId") { header("Authorization", "Bearer ${adminToken()}") }

        fun generate(foodId: Long): ResultActionsDsl =
            mockMvc.post("/api/admin/foods/$foodId/images/generate") { header("Authorization", "Bearer ${adminToken()}") }

        fun lastItemIntent(foodId: Long): String? = dataSource.connection.use { c ->
            c.prepareStatement("SELECT regeneration_intent FROM image_batch_item WHERE food_id = ? ORDER BY id DESC LIMIT 1").use { ps ->
                ps.setLong(1, foodId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

        given("후보 이미지 추가 생성 — POST /api/admin/foods/{id}/images/generate") {
            `when`("READY 음식에 요청하면") {
                then("상태·대표는 그대로, ADDITIONAL 항목이 생기고 갤러리·상세에 additionalInProgress=true, regeneration 은 null") {
                    val food = saveFood("추가생성음식", "images/webp/primary.webp")
                    saveImage(food.id, "images/webp/primary.webp", isPrimary = true, sortOrder = 0)

                    val payload = payloadOf(generate(food.id).andExpect { status { isOk() } })

                    payload.path("contentStatus").asText() shouldBe "READY"
                    lastItemIntent(food.id) shouldBe "ADDITIONAL"
                    val gallery = payloadOf(gallery(food.id))
                    gallery.path("contentStatus").asText() shouldBe "READY"
                    gallery.path("additionalInProgress").asBoolean() shouldBe true
                    gallery.path("regeneration").isNull.shouldBeTrue()
                    gallery.path("items")[0].path("isPrimary").asBoolean() shouldBe true
                    gallery.path("items")[0].path("imageKey").asText() shouldBe "images/webp/primary.webp"
                    payloadOf(detail(food.id)).path("additionalInProgress").asBoolean() shouldBe true
                    foodRepository.findById(food.id).get().imageRef shouldBe "images/webp/primary.webp"
                }
            }

            `when`("READY 가 아닌 음식에 요청하면") {
                then("409 FOOD-011 이다") {
                    val food = saveFood("비공개추가음식", "images/webp/x.webp", FoodContentStatus.PENDING_REVIEW)
                    generate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-011") }
                    }
                }
            }

            `when`("추가 생성이 진행 중인 음식에 다시 요청하면") {
                then("409 FOOD-019 이고 항목은 하나뿐이다") {
                    val food = saveFood("중복추가음식", "images/webp/y.webp")
                    generate(food.id).andExpect { status { isOk() } }

                    generate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-019") }
                    }
                    dataSource.connection.use { c ->
                        c.prepareStatement("SELECT COUNT(*) FROM image_batch_item WHERE food_id = ?").use { ps ->
                            ps.setLong(1, food.id); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                        }
                    } shouldBe 1L
                }
            }

            `when`("다른 트랜잭션이 음식 행을 잠근 채 일반 재생성 항목을 넣고 있으면") {
                then("추가 생성은 커밋을 기다렸다가 409(FOOD-011) — 유니크 위반 500 이 아니다") {
                    val food = saveFood("추가생성경합음식", "images/webp/race.webp")
                    val locked = java.util.concurrent.CountDownLatch(1)
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val regen = executor.submit {
                        org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                            val locked1 = foodRepository.findByIdForUpdate(food.id)!!
                            locked1.contentStatus = FoodContentStatus.PENDING_IMAGE
                            foodRepository.saveAndFlush(locked1)
                            val batch = imageBatchJpaRepository.save(com.kbap.common.domain.food.model.ImageBatch(promptVersion = "v1", model = "gpt-image-2"))
                            imageBatchItemJpaRepository.saveAndFlush(com.kbap.common.domain.food.model.ImageBatchItem(batchId = batch.id, foodId = food.id))
                            locked.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    if (!locked.await(30, java.util.concurrent.TimeUnit.SECONDS)) regen.get(1, java.util.concurrent.TimeUnit.SECONDS)

                    generate(food.id).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-011") }
                    }
                    regen.get(30, java.util.concurrent.TimeUnit.SECONDS)
                }
            }

            `when`("추가 생성이 실패하면") {
                then("음식은 READY·대표 그대로, additionalInProgress=false, regeneration 은 여전히 null 이다 — 재생성 상태에 섞이지 않는다") {
                    val food = saveFood("추가실패음식", "images/webp/z.webp")
                    generate(food.id).andExpect { status { isOk() } }
                    failLastItem(food.id)

                    val gallery = payloadOf(gallery(food.id))
                    gallery.path("contentStatus").asText() shouldBe "READY"
                    gallery.path("additionalInProgress").asBoolean() shouldBe false
                    gallery.path("regeneration").isNull.shouldBeTrue()
                    foodRepository.findById(food.id).get().imageRef shouldBe "images/webp/z.webp"
                }
            }

            `when`("추가 생성이 진행 중인 음식을 재생성하려 하면") {
                then("409 IMAGE-004 — 음식당 진행 중 배치 항목은 하나뿐이다") {
                    val food = saveFood("추가중재생성음식", "images/webp/w.webp")
                    generate(food.id).andExpect { status { isOk() } }

                    regenerate(food.id, "WRONG_IMAGE").andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("IMAGE-004") }
                    }
                }
            }
        }

        given("갤러리의 마지막 재생성 상태") {
            `when`("재생성 이력이 없으면") {
                then("regeneration 이 null 이다") {
                    val food = saveFood("이력없음음식", "images/webp/none.webp")

                    payloadOf(gallery(food.id)).path("regeneration").isNull.shouldBeTrue()
                    payloadOf(detail(food.id)).path("regeneration").isNull.shouldBeTrue()
                }
            }

            `when`("의도·사유를 담아 재생성을 제출하면") {
                then("IN_PROGRESS 와 제출한 의도·사유가 갤러리·상세에 보인다") {
                    val food = saveFood("진행중음식", "images/webp/wip.webp")
                    regenerate(food.id, "WRONG_IMAGE", "음식이 아님").andExpect { status { isOk() } }

                    val regeneration = payloadOf(gallery(food.id)).path("regeneration")
                    regeneration.path("state").asText() shouldBe "IN_PROGRESS"
                    regeneration.path("intent").asText() shouldBe "WRONG_IMAGE"
                    regeneration.path("reason").asText() shouldBe "음식이 아님"
                    regeneration.path("at").isTextual.shouldBeTrue()
                    payloadOf(detail(food.id)).path("regeneration").path("state").asText() shouldBe "IN_PROGRESS"
                }
            }

            `when`("WRONG_IMAGE 재생성이 실패하면") {
                then("FAILED 와 의도가 보이고 음식은 숨김(PENDING_IMAGE) 그대로다") {
                    val food = saveFood("오류실패음식", "images/webp/wrong.webp")
                    regenerate(food.id, "WRONG_IMAGE").andExpect { status { isOk() } }
                    failLastItem(food.id)

                    val payload = payloadOf(gallery(food.id))
                    payload.path("contentStatus").asText() shouldBe "PENDING_IMAGE"
                    payload.path("regeneration").path("state").asText() shouldBe "FAILED"
                    payload.path("regeneration").path("intent").asText() shouldBe "WRONG_IMAGE"
                }
            }

            `when`("REPLACE_BETTER 재생성이 실패해 READY 로 복원된 뒤 조회하면") {
                then("음식은 READY 인데도 FAILED 와 REPLACE_BETTER 가 남는다 — 새로고침해도 흔적이 지워지지 않는다") {
                    val food = saveFood("교체실패음식", "images/webp/better.webp")
                    regenerate(food.id, "REPLACE_BETTER", "더 선명하게").andExpect { status { isOk() } }
                    failLastItem(food.id)
                    dataSource.connection.use { c ->
                        c.prepareStatement("UPDATE food SET content_status = 'READY' WHERE id = ?").use { ps ->
                            ps.setLong(1, food.id); ps.executeUpdate()
                        }
                    }

                    val payload = payloadOf(gallery(food.id))
                    payload.path("contentStatus").asText() shouldBe "READY"
                    payload.path("regeneration").path("state").asText() shouldBe "FAILED"
                    payload.path("regeneration").path("intent").asText() shouldBe "REPLACE_BETTER"
                    payload.path("regeneration").path("reason").asText() shouldBe "더 선명하게"
                }
            }

            `when`("의도 없이(구 어드민) 제출한 재생성이 실패하면") {
                then("FAILED 이고 intent·reason 은 null 이다") {
                    val food = saveFood("의도없음실패음식", "images/webp/legacy.webp")
                    regenerate(food.id, null).andExpect { status { isOk() } }
                    failLastItem(food.id)

                    val regeneration = payloadOf(gallery(food.id)).path("regeneration")
                    regeneration.path("state").asText() shouldBe "FAILED"
                    regeneration.path("intent").isNull.shouldBeTrue()
                    regeneration.path("reason").isNull.shouldBeTrue()
                }
            }

            `when`("마지막 재생성이 성공(DONE)했으면") {
                then("regeneration 이 null 이다 — 보여 줄 진행·실패가 없다") {
                    val food = saveFood("성공음식", "images/webp/done.webp")
                    regenerate(food.id, "REPLACE_BETTER").andExpect { status { isOk() } }
                    finishLastItem(food.id)

                    payloadOf(gallery(food.id)).path("regeneration").isNull.shouldBeTrue()
                }
            }

            `when`("실패한 뒤 다시 제출하면") {
                then("마지막 항목 기준이라 IN_PROGRESS 로 바뀐다") {
                    val food = saveFood("재제출음식", "images/webp/again.webp")
                    regenerate(food.id, "WRONG_IMAGE").andExpect { status { isOk() } }
                    failLastItem(food.id)
                    regenerate(food.id, "WRONG_IMAGE", "두 번째").andExpect { status { isOk() } }

                    val regeneration = payloadOf(gallery(food.id)).path("regeneration")
                    regeneration.path("state").asText() shouldBe "IN_PROGRESS"
                    regeneration.path("reason").asText() shouldBe "두 번째"
                }
            }
        }

        given("어드민 이미지 갤러리 조회") {
            `when`("대표 1장과 추가 2장이 있는 음식을 조회하면") {
                then("대표가 먼저, 이후 정렬 순서로 내려가고 version·contentStatus 가 함께 온다") {
                    val food = saveFood("갤러리불고기", "images/webp/a.webp")
                    saveImage(food.id, "images/webp/b.webp", isPrimary = false, sortOrder = 2)
                    saveImage(food.id, "images/webp/a.webp", isPrimary = true, sortOrder = 0)
                    saveImage(food.id, "images/webp/c.webp", isPrimary = false, sortOrder = 1)

                    val payload = payloadOf(gallery(food.id))
                    payload.path("foodId").asLong() shouldBe food.id
                    payload.path("contentStatus").asText() shouldBe "READY"
                    payload.path("version").asLong() shouldBe food.version
                    payload.path("items").size() shouldBe 3
                    payload.path("items")[0].path("imageKey").asText() shouldBe "images/webp/a.webp"
                    payload.path("items")[0].path("isPrimary").asBoolean() shouldBe true
                    payload.path("items")[0].path("imageUrl").asText() shouldBe "https://cdn.test/images/webp/a.webp"
                    payload.path("items")[1].path("imageKey").asText() shouldBe "images/webp/c.webp"
                    payload.path("items")[2].path("imageKey").asText() shouldBe "images/webp/b.webp"
                }
            }

            `when`("이미지가 없는 음식을 조회하면") {
                then("items 는 빈 배열이다") {
                    val food = saveFood("이미지없음", null)

                    payloadOf(gallery(food.id)).path("items").size() shouldBe 0
                }
            }

            `when`("어드민 토큰 없이 조회하면") {
                then("401 로 거절한다") {
                    val food = saveFood("무토큰음식", null)

                    gallery(food.id, token = null).andExpect { status { isUnauthorized() } }
                }
            }
        }

        fun updateFood(food: Food, imageRef: String?, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.put("/api/admin/foods/${food.id}") {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(
                    mapOf(
                        "koreanName" to food.koreanName,
                        "displayName" to food.displayName,
                        "description" to food.description,
                        "spiciness" to food.spiciness,
                        "contentStatus" to food.contentStatus.name,
                        "imageRef" to imageRef,
                        "version" to food.version,
                    ),
                )
            }

        given("음식 수정의 imageRef 차단") {
            `when`("조회값과 같은 imageRef 를 그대로 되돌려 보내면") {
                then("통과한다 — 수정 폼이 깨지지 않는다") {
                    val food = saveFood("동일imageRef음식", "images/webp/same.webp")

                    updateFood(food, "images/webp/same.webp").andExpect { status { isOk() } }
                }
            }

            `when`("다른 imageRef 로 바꾸려 하면") {
                then("400 FOOD-012 로 거절한다 — 대표 지정 API 가 유일한 경로다") {
                    val food = saveFood("다른imageRef음식", "images/webp/keep.webp")

                    updateFood(food, "images/webp/hijack.webp").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-012") }
                    }

                    foodRepository.findById(food.id).orElseThrow().imageRef shouldBe "images/webp/keep.webp"
                }
            }
        }

        given("대표 이미지 교체") {
            `when`("다른 이미지를 대표로 지정하면") {
                then("대표가 바뀌고 food.image_ref 도 그 키로 갱신된다") {
                    val food = saveFood("대표교체음식", "images/webp/old.webp")
                    saveImage(food.id, "images/webp/old.webp", isPrimary = true, sortOrder = 0)
                    val next = saveImage(food.id, "images/webp/new.webp", isPrimary = false, sortOrder = 1)

                    val payload = payloadOf(setPrimary(food.id, next.id, food.version))
                    payload.path("items")[0].path("imageKey").asText() shouldBe "images/webp/new.webp"
                    payload.path("items")[0].path("isPrimary").asBoolean() shouldBe true

                    foodRepository.findById(food.id).orElseThrow().imageRef shouldBe "images/webp/new.webp"
                }
            }

            `when`("응답의 version 으로 곧바로 다시 대표를 바꾸면") {
                then("충돌 없이 반영된다 — 커밋된 version 을 돌려준다") {
                    val food = saveFood("연속교체음식", "images/webp/a.webp")
                    saveImage(food.id, "images/webp/a.webp", isPrimary = true, sortOrder = 0)
                    val second = saveImage(food.id, "images/webp/b.webp", isPrimary = false, sortOrder = 1)
                    val third = saveImage(food.id, "images/webp/c.webp", isPrimary = false, sortOrder = 2)

                    val version = payloadOf(setPrimary(food.id, second.id, food.version)).path("version").asLong()

                    version shouldBe foodRepository.findById(food.id).orElseThrow().version
                    setPrimary(food.id, third.id, version).andExpect { status { isOk() } }
                }
            }

            `when`("이미 대표인 이미지를 다시 지정하면") {
                then("멱등하게 200 이다") {
                    val food = saveFood("멱등음식", "images/webp/p.webp")
                    val primary = saveImage(food.id, "images/webp/p.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, primary.id, food.version).andExpect { status { isOk() } }
                }
            }

            `when`("이미 대표인 이미지를 다시 지정해도") {
                then("벡터 아웃박스가 쌓이지 않는다 — 바뀐 게 없으면 재색인도 없다") {
                    val food = saveFood("멱등아웃박스음식", "images/webp/idem.webp")
                    val primary = saveImage(food.id, "images/webp/idem.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, primary.id, food.version).andExpect { status { isOk() } }

                    vectorOutboxRepository.findAll().count { it.foodId == food.id } shouldBe 0
                }
            }

            `when`("응답을 못 받은 클라이언트가 같은 대표 지정을 그대로 재시도하면") {
                then("버전이 낡았어도 200 이다 — 이미 그 이미지가 대표이므로 바꿀 게 없다") {
                    val food = saveFood("재시도멱등음식", "images/webp/a.webp")
                    saveImage(food.id, "images/webp/a.webp", isPrimary = true, sortOrder = 0)
                    val next = saveImage(food.id, "images/webp/b.webp", isPrimary = false, sortOrder = 1)

                    setPrimary(food.id, next.id, food.version).andExpect { status { isOk() } }

                    setPrimary(food.id, next.id, food.version).andExpect { status { isOk() } }
                }
            }

            `when`("두 관리자가 같은 version 으로 서로 다른 이미지를 동시에 대표로 지정하면") {
                then("하나는 200, 하나는 409 FOOD-006 이고 대표는 정확히 한 장이다 — 유니크 충돌(COMMON-004·500)이 새지 않는다") {
                    repeat(15) { round ->
                        val food = saveFood("동시대표음식$round", "images/webp/race-old-$round.webp")
                        saveImage(food.id, "images/webp/race-old-$round.webp", isPrimary = true, sortOrder = 0)
                        val candidates = listOf(
                            saveImage(food.id, "images/webp/race-a-$round.webp", isPrimary = false, sortOrder = 1),
                            saveImage(food.id, "images/webp/race-b-$round.webp", isPrimary = false, sortOrder = 2),
                        )
                        val gate = java.util.concurrent.CountDownLatch(1)
                        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
                        val responses = candidates.map { image ->
                            executor.submit<Pair<Int, String>> {
                                gate.await()
                                val response = setPrimary(food.id, image.id, food.version).andReturn().response
                                response.status to mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText()
                            }
                        }
                        executor.shutdown()
                        gate.countDown()
                        val outcomes = responses.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

                        outcomes.map { it.first }.sorted() shouldBe listOf(200, 409)
                        outcomes.single { it.first == 409 }.second shouldBe "FOOD-006"
                        val images = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id)
                        images.count { it.isPrimary } shouldBe 1
                        foodRepository.findById(food.id).orElseThrow().imageRef shouldBe images.single { it.isPrimary }.imageKey
                    }
                }
            }

            `when`("다른 관리자의 대표 지정이 커밋되기 전에 같은 version 으로 다른 이미지를 지정하면") {
                then("앞 요청 뒤로 직렬화돼 409 FOOD-006 이고, 대표 이미지와 food.image_ref 가 어긋나지 않는다") {
                    val food = saveFood("직렬화대표음식", "images/webp/serial-a.webp")
                    val first = saveImage(food.id, "images/webp/serial-a.webp", isPrimary = false, sortOrder = 0)
                    val second = saveImage(food.id, "images/webp/serial-b.webp", isPrimary = false, sortOrder = 1)
                    val promoted = java.util.concurrent.CountDownLatch(1)
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    val other = executor.submit {
                        org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                            adminFoodImageService.setPrimary(food.id, second.id, food.version)
                            promoted.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    executor.shutdown()
                    if (!promoted.await(30, java.util.concurrent.TimeUnit.SECONDS)) other.get(1, java.util.concurrent.TimeUnit.SECONDS)

                    setPrimary(food.id, first.id, food.version).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-006") }
                    }
                    other.get(30, java.util.concurrent.TimeUnit.SECONDS)

                    val images = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id)
                    images.single { it.isPrimary }.id shouldBe second.id
                    foodRepository.findById(food.id).orElseThrow().imageRef shouldBe "images/webp/serial-b.webp"
                }
            }

            `when`("대표 행이 아직 없는 음식에 두 관리자가 동시에 서로 다른 이미지를 대표로 지정하면") {
                then("유니크 충돌 없이 끝나고 대표는 정확히 한 장, food.image_ref 와 일치한다") {
                    repeat(15) { round ->
                        val food = saveFood("대표없는동시음식$round", "images/webp/none-a-$round.webp")
                        val candidates = listOf(
                            saveImage(food.id, "images/webp/none-a-$round.webp", isPrimary = false, sortOrder = 0),
                            saveImage(food.id, "images/webp/none-b-$round.webp", isPrimary = false, sortOrder = 1),
                        )
                        val gate = java.util.concurrent.CountDownLatch(1)
                        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
                        val responses = candidates.map { image ->
                            executor.submit<Pair<Int, String>> {
                                gate.await()
                                val response = setPrimary(food.id, image.id, food.version).andReturn().response
                                response.status to mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText()
                            }
                        }
                        executor.shutdown()
                        gate.countDown()
                        val outcomes = responses.map { it.get(60, java.util.concurrent.TimeUnit.SECONDS) }

                        outcomes.filter { it.first != 200 }.forEach { it shouldBe (409 to "FOOD-006") }
                        (outcomes.count { it.first == 200 } >= 1) shouldBe true
                        val images = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(food.id)
                        images.count { it.isPrimary } shouldBe 1
                        foodRepository.findById(food.id).orElseThrow().imageRef shouldBe images.single { it.isPrimary }.imageKey
                    }
                }
            }

            `when`("version 이 최신이 아니면") {
                then("409 FOOD-006 으로 거절한다") {
                    val food = saveFood("버전충돌음식", "images/webp/v.webp")
                    val image = saveImage(food.id, "images/webp/v2.webp", isPrimary = false, sortOrder = 1)

                    setPrimary(food.id, image.id, food.version + 5).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-006") }
                    }
                }
            }

            `when`("다른 음식의 이미지 id 를 지정하면") {
                then("404 로 거절한다") {
                    val food = saveFood("주인아님음식", "images/webp/x.webp")
                    val other = saveFood("다른음식", "images/webp/y.webp")
                    val otherImage = saveImage(other.id, "images/webp/y.webp", isPrimary = true, sortOrder = 0)

                    setPrimary(food.id, otherImage.id, food.version).andExpect { status { isNotFound() } }
                }
            }
        }
    }
}
