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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodScanNotChargedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var vision: FakeMenuBoardVisionExtractor

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(id: Long, scanCount: Int = 0): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = $scanCount, scan_unlocked = 0 WHERE id = $id")
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
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 스캔은 무료 횟수에서 차감하지 않는다(KB-963)") {
            `when`("남은 무료 횟수가 0 인 회원이 이름 확정·성분표 분석을 하면") {
                then("한도 오류 없이 성공하고 charged=false·freeUntil=null 이며 scan_count 는 그대로다") {
                    val token = member(96301L, scanCount = Member.FREE_SCAN_LIMIT)
                    val front = confirmFront(token).path("payload")
                    front.path("charged").asBoolean() shouldBe false
                    front.path("freeUntil").isNull shouldBe true
                    repeat(2) {
                        val read = label(token).path("payload")
                        read.path("charged").asBoolean() shouldBe false
                        read.path("freeUntil").isNull shouldBe true
                    }
                    scanCountOf(96301L) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                    profileFreeUntil(token).isNull shouldBe true
                    long("SELECT COUNT(*) FROM scan_usage u JOIN scan_operation o ON o.id = u.operation_id WHERE o.member_id = 96301 AND u.entitlement_at_commit = 'NOT_CHARGED'") shouldBe 3L
                }
            }
            `when`("1회 남은 회원이 가공식품 스캔 뒤 메뉴판 스캔을 하면") {
                then("가공식품 스캔은 횟수를 쓰지 않아 메뉴판 스캔이 남은 1회로 성공하고, 메뉴판 스캔만 차감된다") {
                    val token = member(96302L, scanCount = Member.FREE_SCAN_LIMIT - 1)
                    confirmFront(token)
                    label(token)
                    scanCountOf(96302L) shouldBe (Member.FREE_SCAN_LIMIT - 1).toLong()
                    menuScan(96302L, token)
                    scanCountOf(96302L) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                    profileFreeUntil(token).isNull shouldBe true
                }
            }
            `when`("api-docs 를 보면") {
                then("확정·성분표 분석·내 정보 응답에 freeUntil 필드는 남아 있다(가산 원칙 — 항상 null)") {
                    val schemas = mapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8)).path("components").path("schemas")
                    listOf("ProcessedFoodNameConfirmResponse", "ProcessedFoodLabelScanResponse", "MyProfileResponse").forEach { name ->
                        schemas.path(name).path("properties").has("freeUntil") shouldBe true
                    }
                }
            }
        }
    }
}
