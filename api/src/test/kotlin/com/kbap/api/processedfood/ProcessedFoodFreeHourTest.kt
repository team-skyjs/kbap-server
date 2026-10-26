package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.api.scan.FakeMenuBoardVisionExtractor
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodFreeHourTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var freeHour: ProcessedFoodFreeHour
    @Autowired private lateinit var vision: FakeMenuBoardVisionExtractor

    private val mapper = jacksonObjectMapper()
    private val start = Instant.parse("2026-10-27T03:00:00Z")

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
        fun at(minutes: Long) { freeHour.clock = Clock.fixed(start.plus(Duration.ofMinutes(minutes)), ZoneOffset.UTC) }
        fun member(id: Long, scanCount: Int = 0): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = $scanCount, scan_unlocked = 0 WHERE id = $id")
            redis.delete("pf-free:$id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun post(path: String, body: Map<String, Any?>, token: String, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post(path) {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        val shrimpLabel = "원재료명: 소맥분(밀), 새우 10%, 설탕, 식물성유지\n알레르기 유발물질: 밀, 새우 함유"
        fun label(token: String, expected: Int = 200) = post(
            "/api/processed-foods/scan/label",
            mapOf("analysisId" to null, "clientRequestId" to UUID.randomUUID().toString(), "pages" to listOf(mapOf("ocrText" to shrimpLabel)), "lang" to "ko"),
            token,
            expected,
        )
        fun confirmFront(token: String): JsonNode {
            val analysisId = post("/api/processed-foods/scan/name", mapOf("ocrText" to "새우깡"), token).path("payload").path("analysisId").asText()
            return post("/api/processed-foods/scan/confirm", mapOf("analysisId" to analysisId, "reportNo" to "FH-1"), token)
        }
        fun scanCountOf(id: Long) = long("SELECT scan_count FROM member WHERE id = $id")
        fun profileFreeUntil(token: String): JsonNode = mapper.readTree(
            mockMvc.get("/api/members/me/profile") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("freeUntil")
        fun menuScan(memberId: Long, token: String) {
            val path = "scan/$memberId/menu-${UUID.randomUUID()}.jpg"
            execute("INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) VALUES ($memberId, '$path', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))")
            vision.program(path, listOf(ExtractedMenu("Kimchi 김치찌개", "김치찌개", 9000, matchedIdx = 0)))
            mockMvc.post("/api/scans") {
                param("lang", "ko")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("imagePath" to path, "items" to listOf(mapOf("idx" to 0, "rawMenuName" to "김치찌개"))))
            }.andExpect { status { isOk() } }
        }

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('FH-1', '새우깡', '새우깡', '과자', '농심', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        afterSpec {
            freeHour.clock = Clock.systemDefaultZone()
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 스캔 1시간 무료(KB-876)") {
            `when`("가공식품 스캔이 차감되며 성공하면") {
                then("그때부터 고정 60분 동안 앞면 확정·성분표 분석은 차감 0 이고 종료 시각이 늘지 않으며, 61분째 성공은 1회 차감 + 새 60분이다") {
                    val token = member(87601L)
                    at(0)
                    val first = label(token).path("payload")
                    first.path("charged").asBoolean() shouldBe true
                    first.path("freeUntil").asText() shouldBe "2026-10-27T04:00:00Z"
                    scanCountOf(87601L) shouldBe 1L

                    at(30)
                    val front = confirmFront(token).path("payload")
                    front.path("charged").asBoolean() shouldBe false
                    front.path("freeUntil").asText() shouldBe "2026-10-27T04:00:00Z"
                    at(59)
                    val second = label(token).path("payload")
                    second.path("charged").asBoolean() shouldBe false
                    second.path("freeUntil").asText() shouldBe "2026-10-27T04:00:00Z"
                    scanCountOf(87601L) shouldBe 1L
                    profileFreeUntil(token).asText() shouldBe "2026-10-27T04:00:00Z"

                    at(61)
                    val next = label(token).path("payload")
                    next.path("charged").asBoolean() shouldBe true
                    next.path("freeUntil").asText() shouldBe "2026-10-27T05:01:00Z"
                    scanCountOf(87601L) shouldBe 2L
                }
            }
            `when`("메뉴판 스캔은") {
                then("창 안에서도 차감되고, 창 밖에서는 창을 열지 않는다") {
                    val token = member(87602L)
                    at(0)
                    menuScan(87602L, token)
                    scanCountOf(87602L) shouldBe 1L
                    profileFreeUntil(token).isNull shouldBe true

                    label(token)
                    scanCountOf(87602L) shouldBe 2L
                    menuScan(87602L, token)
                    scanCountOf(87602L) shouldBe 3L
                    profileFreeUntil(token).asText() shouldBe "2026-10-27T04:00:00Z"
                }
            }
            `when`("남은 무료 횟수가 0 이면") {
                then("창 안이면 가공식품 스캔이 통과하고, 창 밖이면 기존 한도 오류(SCAN-004)다") {
                    val token = member(87603L, scanCount = Member.FREE_SCAN_LIMIT - 1)
                    at(0)
                    label(token).path("payload").path("charged").asBoolean() shouldBe true
                    scanCountOf(87603L) shouldBe Member.FREE_SCAN_LIMIT.toLong()

                    at(10)
                    label(token).path("payload").path("charged").asBoolean() shouldBe false
                    confirmFront(token).path("payload").path("charged").asBoolean() shouldBe false

                    at(61)
                    label(token, expected = 403).path("code").asText() shouldBe "SCAN-004"
                    profileFreeUntil(token).isNull shouldBe true
                }
            }
            `when`("창이 없을 때 앞면 확정 두 건이 동시에 오면") {
                then("둘 다 첫 성공으로 차감되지 않고 1회만 차감된다") {
                    val token = member(87604L)
                    at(0)
                    val analysisIds = (1..2).map { post("/api/processed-foods/scan/name", mapOf("ocrText" to "새우깡"), token).path("payload").path("analysisId").asText() }
                    val ready = CountDownLatch(2)
                    val go = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    val results = analysisIds.map { id ->
                        pool.submit<JsonNode> {
                            ready.countDown()
                            go.await()
                            post("/api/processed-foods/scan/confirm", mapOf("analysisId" to id, "reportNo" to "FH-1"), token).path("payload")
                        }
                    }
                    ready.await()
                    go.countDown()
                    val charged = results.map { it.get().path("charged").asBoolean() }
                    pool.shutdown()

                    charged.count { it } shouldBe 1
                    scanCountOf(87604L) shouldBe 1L
                }
            }
            `when`("api-docs 를 보면") {
                then("확정·성분표 분석·내 정보 응답에 freeUntil 이 있다") {
                    val schemas = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8)).path("components").path("schemas")
                    listOf("ProcessedFoodNameConfirmResponse", "ProcessedFoodLabelScanResponse", "MyProfileResponse").forEach { name ->
                        schemas.path(name).path("properties").has("freeUntil") shouldBe true
                    }
                }
            }
        }
    }
}
