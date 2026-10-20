package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminMemberQueryService
import com.kbap.api.notification.HelpfulPushListener
import com.kbap.common.domain.notification.PushDispatchResult
import com.kbap.common.domain.notification.PushRequest
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodReviewTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var foods: FoodJpaRepository
    @Autowired private lateinit var writeSwitch: ProcessedFoodReviewSwitch
    @Autowired private lateinit var adminMembers: AdminMemberQueryService
    @Autowired private lateinit var uploadedImages: UploadedImageJpaRepository
    @Autowired private lateinit var processedFoodRepository: ProcessedFoodProductJpaRepository

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                if (statement.trimStart().startsWith("SELECT")) st.executeQuery(statement).use { rs -> if (rs.next()) rs.getLong(1) else -1L }
                else { st.executeUpdate(statement, java.sql.Statement.RETURN_GENERATED_KEYS); st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
            }
        }
        var seq = 0
        fun product(name: String, status: String = "ACTIVE", seenDaysAgo: Int = 0): Long = sql(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES ('PR${System.nanoTime() % 1_000_000_000}${seq++}', '$name', '$name', '과자', '농심', 'http://www.haccp.or.kr/a.jpg', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6) - INTERVAL $seenDaysAgo DAY, '$status', NOW(6), NOW(6))",
        )
        fun member(tag: String): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "$tag-${System.nanoTime()}", null)))
        fun token(m: Member) = tokens.issueAccessToken(m.id, MemberRole.USER)
        fun json(body: String): JsonNode = mapper.readTree(body)

        fun write(productId: Long, author: Member?, body: String = "바삭하고 짭짤해서 맛있어요", rating: Int = 4, version: String = "3.2", expected: Int = 200): JsonNode = json(
            mvc.post("/api/processed-foods/$productId/reviews") {
                header("X-API-Version", version)
                author?.let { header("Authorization", "Bearer ${token(it)}") }
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("rating" to rating, "content" to body))
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun get(path: String, version: String, viewer: Member? = null, expected: Int = 200): JsonNode = json(
            mvc.get(path) {
                header("X-API-Version", version)
                viewer?.let { header("Authorization", "Bearer ${token(it)}") }
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun foodReview(author: Member): Long {
            val food = foods.save(Food(koreanName = "가공리뷰음식", description = "설명", contentStatus = FoodContentStatus.READY))
            return json(
                mvc.post("/api/reviews") {
                    header("X-API-Version", "1.0")
                    header("Authorization", "Bearer ${token(author)}")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("foodId" to food.id, "rating" to 5, "content" to "국물이 진하고 고기가 부드러워요"))
                }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("reviewId").asLong()
        }

        beforeEach {
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'PR%'")
            writeSwitch.writeEnabled = true
        }
        afterSpec {
            writeSwitch.writeEnabled = false
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'PR%'")
        }

        given("가공식품 리뷰 쓰기 플래그") {
            `when`("플래그가 꺼져 있으면") {
                then("작성은 403 PFOOD-005 이고 행·카운터를 남기지 않는다") {
                    writeSwitch.writeEnabled = false
                    val author = member("pfr-off")
                    write(product("플래그과자"), author, expected = 403).path("code").asText() shouldBe "PFOOD-005"
                    sql("SELECT COUNT(*) FROM food_review") shouldBe 0
                    sql("SELECT review_count FROM member WHERE id = ${author.id}") shouldBe 0
                }
            }
        }

        given("가공식품 리뷰 작성(3.2)") {
            `when`("회원이 10자 이상 본문으로 쓰면") {
                then("가공식품 대상 리뷰가 생기고 회원 리뷰 수·스캔 해제·랭킹 10점, 음식 쪽 카운터는 그대로다") {
                    val author = member("pfr-write")
                    val productId = product("새우깡")
                    val payload = write(productId, author).path("payload")
                    payload.path("targetType").asText() shouldBe "PROCESSED_FOOD"
                    payload.path("processedFood").path("processedFoodId").asLong() shouldBe productId
                    payload.path("processedFood").path("productName").asText() shouldBe "새우깡"
                    payload.has("food") shouldBe false
                    payload.path("place").isNull shouldBe true
                    val reviewId = payload.path("reviewId").asLong()
                    sql("SELECT COUNT(*) FROM food_review WHERE id = $reviewId AND target_type = 'PROCESSED_FOOD' AND processed_food_id = $productId AND food_id IS NULL") shouldBe 1
                    sql("SELECT review_count FROM member WHERE id = ${author.id}") shouldBe 1
                    sql("SELECT scan_unlocked FROM member WHERE id = ${author.id}") shouldBe 1
                    sql("SELECT unique_reviewed_food_count FROM member WHERE id = ${author.id}") shouldBe 0
                    sql("SELECT unique_food_count_delta FROM member_ranking_event WHERE review_id = $reviewId AND event = 'REVIEW_CREATED'") shouldBe 0
                    sql("SELECT review_count_delta FROM member_ranking_event WHERE review_id = $reviewId AND event = 'REVIEW_CREATED'") shouldBe 1
                }
            }
            `when`("본문이 10자 미만이거나 비회원이거나 없는·생산종료 제품이면") {
                then("REVIEW-005·401·PFOOD-001·PFOOD-002 로 막는다") {
                    val author = member("pfr-invalid")
                    write(product("짧은글과자"), author, body = "맛있어요", expected = 400).path("code").asText() shouldBe "REVIEW-005"
                    write(product("비회원과자"), null, expected = 401)
                    write(Long.MAX_VALUE, author, expected = 404).path("code").asText() shouldBe "PFOOD-001"
                    sql("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990201, 0, 'processedFoodMirrorJob', 'pfr-990201')")
                    sql("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990201, 0, 990201, NOW(6) - INTERVAL 1 HOUR, NOW(6) - INTERVAL 1 HOUR, NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
                    try {
                        write(product("단종과자", seenDaysAgo = 30), author, expected = 410).path("code").asText() shouldBe "PFOOD-002"
                    } finally {
                        sql("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990201")
                        sql("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990201")
                    }
                    sql("SELECT COUNT(*) FROM food_review") shouldBe 0
                }
            }
            `when`("3.1 헤더로 쓰면") {
                then("그 버전에는 경로가 없어 404 이고 행이 생기지 않는다") {
                    write(product("구버전과자"), member("pfr-old"), version = "3.1", expected = 404)
                    sql("SELECT COUNT(*) FROM food_review") shouldBe 0
                }
            }
        }

        given("가공식품 리뷰 읽기") {
            `when`("제품 리뷰 목록을 비회원이 보면") {
                then("최신순으로 보이고 다음 커서로 이어진다") {
                    val productId = product("목록과자")
                    val ids = (1..3).map { write(productId, member("pfr-list-$it")).path("payload").path("reviewId").asLong() }
                    val page = get("/api/processed-foods/$productId/reviews?lang=en", "3.2").path("payload")
                    page.path("items").map { it.path("reviewId").asLong() } shouldBe ids.reversed()
                    page.path("items")[0].path("processedFood").path("processedFoodId").asLong() shouldBe productId
                }
            }
            `when`("공개 피드·내 리뷰를 3.2 와 3.1 로 보면") {
                then("3.2 는 가공식품 리뷰를 섞고 3.1 이하는 음식 리뷰만 본다") {
                    val author = member("pfr-feed")
                    val foodReviewId = foodReview(author)
                    val productReviewId = write(product("피드과자"), author).path("payload").path("reviewId").asLong()
                    get("/api/reviews?lang=en", "3.2").path("payload").path("items").map { it.path("reviewId").asLong() } shouldBe listOf(productReviewId, foodReviewId)
                    get("/api/reviews?lang=en", "3.1").path("payload").path("items").map { it.path("reviewId").asLong() } shouldBe listOf(foodReviewId)
                    get("/api/reviews/me?lang=en", "3.2", author).path("payload").path("items").map { it.path("reviewId").asLong() } shouldBe listOf(productReviewId, foodReviewId)
                    get("/api/reviews/me?lang=en", "3.1", author).path("payload").path("items").map { it.path("reviewId").asLong() } shouldBe listOf(foodReviewId)
                }
            }
            `when`("3.2 피드를 리뷰 많은 순(food_review_count)으로 보면 — Codex P2") {
                then("음식은 그 음식, 가공식품은 그 제품의 리뷰 수로 정렬된다") {
                    val foodReviewId = foodReview(member("pfr-sort-food"))
                    val productId = product("정렬과자")
                    val productReviewIds = (1..2).map { write(productId, member("pfr-sort-$it")).path("payload").path("reviewId").asLong() }
                    get("/api/reviews?lang=en&sort=food_review_count", "3.2").path("payload").path("items").map { it.path("reviewId").asLong() } shouldBe productReviewIds.reversed() + foodReviewId
                }
            }
            `when`("리뷰 단건을 보면") {
                then("가공식품 리뷰도 제품 요약과 함께 보인다(번역·좋아요가 같은 가시성을 쓴다)") {
                    val productId = product("단건과자")
                    val reviewId = write(productId, member("pfr-one")).path("payload").path("reviewId").asLong()
                    get("/api/reviews/$reviewId?lang=en", "3.2").path("payload").path("processedFood").path("processedFoodId").asLong() shouldBe productId
                }
            }
            `when`("제품 상세·검색을 보면") {
                then("리뷰 수와 평균 별점이 채워진다") {
                    val productId = product("집계과자")
                    write(productId, member("pfr-agg-1"), rating = 5)
                    write(productId, member("pfr-agg-2"), rating = 2)
                    val detail = get("/api/processed-foods/$productId", "3.2").path("payload")
                    detail.path("reviewSummary").path("count").asLong() shouldBe 2
                    detail.path("reviewSummary").path("avgRating").asDouble() shouldBe 3.5
                    val item = get("/api/processed-foods?query=집계과자", "3.2").path("payload").path("items")[0]
                    item.path("reviewCount").asLong() shouldBe 2
                    item.path("avgRating").asDouble() shouldBe 3.5
                    get("/api/processed-foods/${product("무리뷰과자")}", "3.2").path("payload").path("reviewSummary").path("count").asLong() shouldBe 0
                    get("/api/processed-foods?query=무리뷰과자", "3.2").path("payload").path("items")[0].path("reviewCount").asLong() shouldBe 0
                }
            }
            `when`("이름 스캔 후보 카드를 보면") {
                then("리뷰를 집계하지 않으므로 reviewCount·avgRating 이 null 이다(0 으로 보이지 않게)") {
                    val productId = product("후보과자")
                    write(productId, member("pfr-cand-review"))
                    val candidate = json(
                        mvc.post("/api/processed-foods/scan/name") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer ${token(member("pfr-cand"))}")
                            contentType = MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("ocrText" to "농심\n후보과자", "lang" to "ko"))
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("candidates")[0]
                    candidate.path("id").asLong() shouldBe productId
                    candidate.path("reviewCount").isNull shouldBe true
                    candidate.path("avgRating").isNull shouldBe true
                }
            }
        }

        given("가공식품 리뷰 수정·삭제·좋아요(/reviews/{id} 재사용)") {
            `when`("작성자가 식당 전용 값과 함께 수정하면") {
                then("본문·별점만 바뀌고 서빙·친절 평점·위치는 비어 있다") {
                    val author = member("pfr-edit")
                    val reviewId = write(product("수정과자"), author).path("payload").path("reviewId").asLong()
                    val payload = json(
                        mvc.patch("/api/reviews/$reviewId") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer ${token(author)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("rating" to 2, "servingSpeed" to 5, "staffKindness" to 5, "content" to "다시 먹어 보니 조금 짜요", "place" to mapOf("source" to "MANUAL", "name" to "편의점")))
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload")
                    payload.path("rating").asInt() shouldBe 2
                    payload.path("servingSpeed").asInt() shouldBe 0
                    payload.path("staffKindness").asInt() shouldBe 0
                    payload.path("place").isNull shouldBe true
                    payload.path("processedFood").path("productName").asText() shouldBe "수정과자"
                }
            }
            `when`("작성자가 지우면") {
                then("회원 리뷰 수만 줄고 제품 목록에서 사라진다") {
                    val author = member("pfr-del")
                    val productId = product("삭제과자")
                    val reviewId = write(productId, author).path("payload").path("reviewId").asLong()
                    mvc.delete("/api/reviews/$reviewId") { header("X-API-Version", "3.2"); header("Authorization", "Bearer ${token(author)}") }.andExpect { status { isOk() } }
                    sql("SELECT review_count FROM member WHERE id = ${author.id}") shouldBe 0
                    get("/api/processed-foods/$productId/reviews?lang=en", "3.2").path("payload").path("items").size() shouldBe 0
                }
            }
            `when`("작성자가 탈퇴하면") {
                then("리뷰는 남고 작성자 탈퇴로 표시된다(음식 리뷰와 같은 의미)") {
                    val author = member("pfr-withdraw")
                    val productId = product("탈퇴과자")
                    write(productId, author)
                    mvc.patch("/api/auth/withdraw") { header("X-API-Version", "3.2"); header("Authorization", "Bearer ${token(author)}") }.andExpect { status { isOk() } }
                    val item = get("/api/processed-foods/$productId/reviews?lang=en", "3.2").path("payload").path("items")[0]
                    item.path("authorWithdrawn").asBoolean() shouldBe true
                }
            }
        }

        given("분기 지점") {
            `when`("다른 회원이 가공식품 리뷰에 좋아요를 누르면") {
                then("도움돼요 알림이 작성자에게 제품명으로 간다") {
                    val sent = mutableListOf<PushRequest>()
                    val listener = HelpfulPushListener({ sent += it; PushDispatchResult(1, 0) }, foods, processedFoodRepository)
                    val author = member("pfr-like")
                    val productId = product("좋아요과자")
                    listener.handle(ReviewLiked(77, author.id, null, productId))
                    sent.single().memberIds shouldBe listOf(author.id)
                    sent.single().argsByLang.values.map { it["food"] }.toSet() shouldBe setOf("좋아요과자")
                }
            }
            `when`("어드민이 회원 리뷰 목록을 보면") {
                then("가공식품 리뷰에 대상 타입·제품 id·제품명이 붙는다") {
                    val author = member("pfr-admin")
                    val productId = product("어드민과자")
                    val reviewId = write(productId, author).path("payload").path("reviewId").asLong()
                    val item = adminMembers.getMemberReviewPage(author.id, 1).items.single { it.id == reviewId }
                    item.targetType shouldBe "PROCESSED_FOOD"
                    item.processedFoodId shouldBe productId
                    item.targetName shouldBe "어드민과자"
                }
            }
            `when`("가공식품 리뷰에 붙은 사진 경로를 정리 배치가 보면") {
                then("참조된 사진으로 센다") {
                    val author = member("pfr-photo")
                    val productId = product("사진과자")
                    sql("INSERT INTO food_review (member_id, target_type, food_id, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, image_refs, status, created_at, updated_at, version) " +
                        "VALUES (${author.id}, 'PROCESSED_FOOD', NULL, $productId, 4, 0, 0, '사진이 붙은 가공식품 리뷰', JSON_ARRAY('local/images/review/pfr/1.webp'), 'ACTIVE', NOW(6), NOW(6), 0)")
                    uploadedImages.countReferencesTo("local/images/review/pfr/1.webp") shouldBe 1
                }
            }
        }
    }
}
