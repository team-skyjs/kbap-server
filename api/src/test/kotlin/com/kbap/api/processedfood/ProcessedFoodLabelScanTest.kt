package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodLabelScanTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } } }
        fun member(id: Long, vararg avoiding: String, scanCount: Int = 0): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id, *avoiding)
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
        fun label(token: String, analysisId: String?, vararg pages: String, expected: Int = 200) =
            post("/api/processed-foods/scan/label", mapOf("analysisId" to analysisId, "pages" to pages.map { mapOf("ocrText" to it) }, "lang" to "ko"), token, expected)
        fun scanCountOf(memberId: Long) = long("SELECT scan_count FROM member WHERE id = $memberId")
        val shrimpLabel = arrayOf(
            "원재료명: 소맥분(밀), 새우 10%, 설탕, 식물성유지\n알레르기 유발물질: 밀, 새우 함유",
            "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.",
        )

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                    "('PL-1', '새우깡', '새우깡', '과자', '농심', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("성분표 분석 — 새 분석(analysisId 없음)") {
            `when`("원재료·표시란·같은 제조시설 문구가 있는 여러 면을 보내면") {
                then("표시란 검출·함유 가능·회피 대조가 오고 1회 차감된다") {
                    val token = member(88301L, "SEAFOOD", "PEANUT")
                    val p = label(token, null, *shrimpLabel).path("payload")
                    p.path("state").asText() shouldBe "LABEL_RESULT"
                    p.path("charged").asBoolean() shouldBe true
                    p.path("labelSource").asText() shouldBe "DECLARED"
                    p.path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SHRIMP")
                    p.path("mayContain").map { it.path("code").asText() } shouldBe listOf("PEANUT")
                    val avoidance = p.path("avoidance").associate { it.path("code").asText() to it }
                    avoidance.getValue("SEAFOOD").path("match").asText() shouldBe "DETECTED"
                    avoidance.getValue("PEANUT").path("match").asText() shouldBe "UNCONFIRMED"
                    p.toString() shouldNotContain "SAFE"
                    p.path("rawText").asText().lines().size shouldBe 3
                    scanCountOf(88301L) shouldBe 1
                    long("SELECT COUNT(*) FROM scan_usage") shouldBe 1
                }
            }
            `when`("상호·바코드처럼 원재료를 못 읽으면") {
                then("400 PFOOD-006 이고 분석도 차감도 남기지 않는다") {
                    val token = member(88302L)
                    label(token, null, "농심\n8801043014816", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    label(token, null, "오리온 90g", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    long("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                    scanCountOf(88302L) shouldBe 0
                }
            }
            `when`("검출이 하나라도 있으면") {
                then("토큰 수와 무관하게 성공이다") {
                    val token = member(88303L)
                    label(token, null, "우유 함유").path("payload").path("detections").map { it.path("code").asText() } shouldBe listOf("MILK")
                }
            }
            `when`("무료 횟수를 다 쓴 회원이면") {
                then("403 SCAN-004 이고 분석을 남기지 않는다") {
                    val token = member(88304L, scanCount = 3)
                    label(token, null, *shrimpLabel, expected = 403).path("code").asText() shouldBe "SCAN-004"
                    long("SELECT COUNT(*) FROM scan_operation") shouldBe 0
                    scanCountOf(88304L) shouldBe 3
                }
            }
        }

        given("성분표 분석 — 이름 스캔에서 이어가기") {
            `when`("후보 없음(NO_CANDIDATE) 분석 id 로 성분표를 보내면") {
                then("같은 분석에 결과가 붙고 1회 차감, 같은 id 재호출은 갱신만(차감 0), 판독 실패 재호출은 이전 결과 유지 400") {
                    val token = member(88305L)
                    val id = post("/api/processed-foods/scan/name", mapOf("ocrText" to "알수없는수입과자이름"), token).path("payload").path("analysisId").asText()
                    label(token, id, *shrimpLabel).path("payload").also {
                        it.path("analysisId").asText() shouldBe id
                        it.path("charged").asBoolean() shouldBe true
                    }
                    long("SELECT COUNT(*) FROM scan_operation") shouldBe 1
                    label(token, id, "원재료명: 감자, 식물성유지, 정제소금").path("payload").also {
                        it.path("charged").asBoolean() shouldBe false
                    }
                    label(token, id, "농심", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    long("SELECT COUNT(*) FROM scan_operation WHERE lifecycle = 'LABEL_RESULT' AND JSON_EXTRACT(request_document, '$.label.rawText') LIKE '%감자%'") shouldBe 1
                    scanCountOf(88305L) shouldBe 1
                    post("/api/processed-foods/scan/confirm", mapOf("analysisId" to id, "reportNo" to "PL-1"), token, expected = 409).path("code").asText() shouldBe "PFOOD-004"
                }
            }
            `when`("후보 확정 대기(CANDIDATES) 분석이나 없는·남의 분석 id 면") {
                then("409 PFOOD-007, 404 PFOOD-003") {
                    val token = member(88306L)
                    val candidates = post("/api/processed-foods/scan/name", mapOf("ocrText" to "농심\n새우깡"), token).path("payload")
                    candidates.path("state").asText() shouldBe "CANDIDATES"
                    label(token, candidates.path("analysisId").asText(), *shrimpLabel, expected = 409).path("code").asText() shouldBe "PFOOD-007"
                    label(token, UUID.randomUUID().toString(), *shrimpLabel, expected = 404).path("code").asText() shouldBe "PFOOD-003"
                    val mine = label(token, null, *shrimpLabel).path("payload").path("analysisId").asText()
                    label(member(88307L), mine, *shrimpLabel, expected = 404).path("code").asText() shouldBe "PFOOD-003"
                    scanCountOf(88306L) shouldBe 1
                }
            }
        }
    }
}
