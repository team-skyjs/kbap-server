package com.kbap.api.reviewbot

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.api.image.ImageUploadProperties
import com.kbap.api.review.ProcessedFoodReviewSwitch
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

@IntegrationTest
class ReviewBotProcessedFoodComposeTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var accountService: ReviewBotAccountService
    @Autowired private lateinit var fakeGenerator: FakeReviewTextGenerator
    @Autowired private lateinit var writeSwitch: ProcessedFoodReviewSwitch
    @Autowired private lateinit var uploadProperties: ImageUploadProperties
    @Autowired private lateinit var photoStorage: FakeStorageObjectStore
    @Autowired private lateinit var photoService: ReviewBotPhotoService

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun rows(sql: String): List<List<Any?>> = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) (1..rs.metaData.columnCount).map { rs.getObject(it) } else null }.toList() }
            }
        }
        fun compose(body: Map<String, Any?>, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post("/api/admin/review-bots/processed-foods/reviews/compose") {
                header("X-API-Version", "3.2")
                header("X-Target-Environment", "dev")
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun poolPath(n: Int) = ReviewBotPhotoPool.poolPrefix(uploadProperties.keyPrefix) + ReviewBotPhotoPool.keyOf("processed:19760342001242") + "/$n.webp"

        var noodle = 0L

        beforeSpec { writeSwitch.writeEnabled = true }
        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            fakeGenerator.reset()
            photoStorage.heads.clear()
            accountService.ensureBots(5)
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('19760342001242', '육개장사발면', '${ProcessedFoodSnapshot.normalizeName("육개장사발면")}', '유탕면', '농심', '소맥분, 팜유, 쇠고기', NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            noodle = (rows("SELECT id FROM processed_food_product WHERE report_no = '19760342001242'").single().single() as Number).toLong()
            (1..2).forEach { photoStorage.stub(poolPath(it), "image/webp", 100_000L + it) }
            photoService.sync()
        }
        afterSpec {
            writeSwitch.writeEnabled = false
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("지정 사진·작성일로 봇 가공식품 리뷰 1건 작성(S-950)") {
            `when`("풀에 등록된 사진과 과거 작성일로 실행하면") {
                then("그 사진·작성일·별점으로 봇 리뷰 1건을 저장하고, dryRun 은 저장하지 않는다") {
                    val createdAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusHours(2).truncatedTo(ChronoUnit.SECONDS)
                    val preview = compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1)), "createdAt" to createdAt.toString(), "rating" to 5)).path("payload")
                    preview.path("dryRun").asBoolean() shouldBe true
                    preview.path("reviewId").isNull shouldBe true
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "0"

                    val saved = compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1)), "createdAt" to createdAt.toString(), "rating" to 5, "dryRun" to false)).path("payload")
                    val row = rows("SELECT r.processed_food_id, r.rating, CAST(r.image_refs AS CHAR), r.created_at FROM food_review r JOIN member m ON m.id = r.member_id WHERE m.is_bot = 1 AND r.id = ${saved.path("reviewId").asLong()}").single()
                    (row[0] as Number).toLong() shouldBe noodle
                    (row[1] as Number).toInt() shouldBe 5
                    row[2].toString().contains(poolPath(1)) shouldBe true
                    (row[3] as java.time.LocalDateTime) shouldBe createdAt.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime()
                }
            }
            `when`("사진이 봇 사진 풀 밖이면") {
                then("400 REVIEW-003 이고 저장하지 않는다") {
                    val outside = uploadProperties.keyPrefix.trim('/') + "/images/review/2026/11/1_abc.webp"
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(outside), "dryRun" to false), 400).path("code").asText() shouldBe "REVIEW-003"
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "0"
                }
            }
            `when`("dryRun 이어도 사진이 풀 밖이거나 저장소에서 사라졌으면") {
                then("미리보기도 400 REVIEW-003 이고 생성기를 부르지 않는다") {
                    val outside = uploadProperties.keyPrefix.trim('/') + "/images/review/2026/11/1_abc.webp"
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(outside)), 400).path("code").asText() shouldBe "REVIEW-003"
                    photoStorage.heads.remove(poolPath(1))
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1)), "dryRun" to false), 400).path("code").asText() shouldBe "REVIEW-003"
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1))), 400).path("code").asText() shouldBe "REVIEW-003"
                    fakeGenerator.processedRequests.size shouldBe 0
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "0"
                }
            }
            `when`("이미 다른 리뷰에 쓰인 사진이면") {
                then("두 번째 작성은 400 REVIEW-003") {
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(2)), "dryRun" to false))
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(2)), "dryRun" to false), 400).path("code").asText() shouldBe "REVIEW-003"
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "1"
                }
            }
            `when`("작성일이 묶음 마지막 리뷰보다 뒤가 아니면") {
                then("400 COMMON-002(reason=createdAt) 이고, 뒤면 저장된다") {
                    val first = compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1)), "dryRun" to false)).path("payload")
                    val firstAt = rows("SELECT created_at FROM food_review WHERE id = ${first.path("reviewId").asLong()}").single().single() as java.time.LocalDateTime
                    val before = firstAt.minusHours(1).atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
                    val rejected = compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(2)), "createdAt" to before, "dryRun" to false), 400)
                    rejected.path("code").asText() shouldBe "COMMON-002"
                    rejected.path("payload").path("reason").asText() shouldBe "createdAt"
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "1"
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(2)), "dryRun" to false))
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "2"
                }
            }
            `when`("작성일이 미래면") {
                then("400 COMMON-002 이고 저장하지 않는다") {
                    val future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString()
                    compose(mapOf("productId" to noodle, "imagePaths" to listOf(poolPath(1)), "createdAt" to future, "dryRun" to false), 400).path("code").asText() shouldBe "COMMON-002"
                    rows("SELECT COUNT(*) FROM food_review").single().single().toString() shouldBe "0"
                }
            }
        }
    }
}
