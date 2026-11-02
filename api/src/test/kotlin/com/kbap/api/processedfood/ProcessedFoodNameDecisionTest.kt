package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.NameCandidate
import com.kbap.common.port.llm.NameDecision
import com.kbap.common.port.llm.NameDecisionImageUnavailable
import com.kbap.common.port.llm.NameDecisionRefused
import com.kbap.common.port.llm.NameDecisionUsage
import com.kbap.common.port.llm.ProcessedFoodNameDecider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.net.SocketTimeoutException
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodNameDecisionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var decisions: ProcessedFoodNameDecisionService

    private val mapper = jacksonObjectMapper()

    private class FakeDecider : ProcessedFoodNameDecider {
        val calls = mutableListOf<Pair<String, List<NameCandidate>>>()
        var behavior: () -> NameDecision = { NameDecision(1, 0.92, "새우깡", "claude-haiku-5-5", 2000, 40) }

        override fun decide(imageUrl: String, candidates: List<NameCandidate>): NameDecision {
            synchronized(calls) { calls += imageUrl to candidates }
            return behavior()
        }
    }

    init {
        val fake = FakeDecider()
        val memberId = 89301L
        val ownPath = "local/images/packaged-scans/2026/10/${memberId}_front.jpg"
        val ownMenuScanPath = "local/images/scans/2026/10/${memberId}_menu.jpg"

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun string(sql: String): String? = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
        }

        fun seedProduct(reportNo: String, name: String, manufacturer: String) {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, " +
                    "source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                    "('$reportNo', '$name', '$name', '과자', '$manufacturer', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }

        lateinit var token: String

        fun scanName(body: Map<String, Any?>): JsonNode =
            mapper.readTree(
                mockMvc.post("/api/processed-foods/scan/name") {
                    header("X-API-Version", "3.2")
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        fun scanAndSettle(imagePath: String?): JsonNode =
            scanName(mapOf("ocrText" to "농심\n새우깡\n90g", "imagePath" to imagePath)).also { decisions.awaitIdle() }

        fun decisionOf(analysisId: String): JsonNode? =
            string("SELECT JSON_EXTRACT(request_document, '$.decision') FROM scan_operation WHERE request_key = UUID_TO_BIN('$analysisId')")
                ?.let(mapper::readTree)

        fun reportNos(payload: JsonNode): List<String> = payload.path("candidates").map { it.path("reportNo").asText() }

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            seedProduct("ND-1", "새우깡", "농심_구미")
            seedProduct("ND-2", "새우깡블랙", "농심_구미")
            FoodTestSeed.seedMemberAvoiding(dataSource, memberId)
            execute(
                "INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes, status, created_at, updated_at) " +
                    "VALUES ($memberId, '$ownPath', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6)), ($memberId, '$ownMenuScanPath', 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))",
            )
            token = tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
            decisions.decider = fake
        }
        beforeEach {
            fake.calls.clear()
            fake.behavior = { NameDecision(1, 0.92, "새우깡", "claude-haiku-5-5", 2000, 40) }
        }
        afterSpec {
            decisions.decider = null
            decisions.dailyBudgetKrw = 2000.0
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("이름 스캔 그림자 판별 — imagePath 동봉") {
            `when`("본인 스캔 사진 경로를 보내면") {
                then("응답 후보는 사진 없을 때와 같고, 사진 URL 과 응답 후보 순서 그대로 판별을 불러 decision 을 저장한다") {
                    val plain = scanAndSettle(null)
                    val shadowed = scanAndSettle(ownPath)

                    reportNos(shadowed) shouldBe reportNos(plain)
                    shadowed.path("state").asText() shouldBe plain.path("state").asText()
                    fake.calls.size shouldBe 1
                    fake.calls[0].first shouldBe "https://cdn.test/$ownPath"
                    fake.calls[0].second.map { it.name } shouldBe shadowed.path("candidates").map { it.path("productName").asText() }
                    val decision = decisionOf(shadowed.path("analysisId").asText())!!
                    decision.path("model").asText() shouldBe "claude-haiku-5-5"
                    decision.path("choice").asText() shouldBe reportNos(shadowed).first()
                    decision.path("confidence").asDouble() shouldBe 0.92
                    decision.path("readName").asText() shouldBe "새우깡"
                    decision.path("costKrw").asDouble() shouldBe (0.33 plusOrMinus 0.005)
                    decision.has("latencyMs") shouldBe true
                    decision.has("at") shouldBe true
                    decision.has("error") shouldBe false
                    decisionOf(plain.path("analysisId").asText()) shouldBe null
                }
            }

            `when`("판별 뒤 사용자가 확정하면") {
                then("decision 이 남은 채 selectedReportNo 가 덧붙는다") {
                    val scanned = scanAndSettle(ownPath)
                    val analysisId = scanned.path("analysisId").asText()

                    mockMvc.post("/api/processed-foods/scan/confirm") {
                        header("X-API-Version", "3.2")
                        header("Authorization", "Bearer $token")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("analysisId" to analysisId, "reportNo" to "ND-2"))
                    }.andExpect { status { isOk() } }

                    decisionOf(analysisId)!!.path("choice").asText() shouldBe reportNos(scanned).first()
                    string("SELECT JSON_UNQUOTE(JSON_EXTRACT(request_document, '$.selectedReportNo')) FROM scan_operation WHERE request_key = UUID_TO_BIN('$analysisId')") shouldBe "ND-2"
                }
            }

            `when`("판별이 끝나면") {
                then("llm_call_cost 에 모델·토큰·비용 행이 남는다") {
                    execute("DELETE FROM llm_call_cost")
                    scanAndSettle(ownPath)

                    var row: String? = null
                    repeat(100) {
                        row = row ?: string("SELECT CONCAT(model_name, '/', input_tokens, '/', output_tokens, '/', cost_krw > 0) FROM llm_call_cost")
                        if (row == null) Thread.sleep(50)
                    }
                    row shouldBe "claude-haiku-5-5/2000/40/1"
                }
            }

            `when`("모델이 토큰을 쓰고 거절하면") {
                then("llm_call_cost 1행과 함께 error=refusal·토큰·비용을 남긴다") {
                    execute("DELETE FROM llm_call_cost")
                    fake.behavior = { throw NameDecisionRefused(NameDecisionUsage("claude-haiku-5-5", 2000, 40)) }

                    val decision = decisionOf(scanAndSettle(ownPath).path("analysisId").asText())!!

                    decision.path("error").asText() shouldBe "refusal"
                    decision.path("inputTokens").asLong() shouldBe 2000
                    decision.path("outputTokens").asLong() shouldBe 40
                    decision.path("costKrw").asDouble() shouldBe (0.33 plusOrMinus 0.005)
                    var row: String? = null
                    repeat(100) {
                        row = row ?: string("SELECT CONCAT(COUNT(*), '/', MAX(model_name), '/', MAX(input_tokens)) FROM llm_call_cost HAVING COUNT(*) > 0")
                        if (row == null) Thread.sleep(50)
                    }
                    row shouldBe "1/claude-haiku-5-5/2000"
                }
            }

            `when`("모델이 후보 순번 범위 밖을 답하면") {
                then("choice 없이 error=outside 와 읽은 이름·비용을 남긴다") {
                    fake.behavior = { NameDecision(21, 0.8, "새우깡", "claude-haiku-5-5", 2000, 40) }

                    val decision = decisionOf(scanAndSettle(ownPath).path("analysisId").asText())!!

                    decision.path("error").asText() shouldBe "outside"
                    decision.has("choice") shouldBe false
                    decision.path("readName").asText() shouldBe "새우깡"
                    decision.path("costKrw").asDouble() shouldBe (0.33 plusOrMinus 0.005)
                }
            }

            `when`("모델이 후보 밖이라고 답하면") {
                then("choice 는 null 로 저장된다") {
                    fake.behavior = { NameDecision(null, 0.7, "꼬북칩", "claude-haiku-5-5", 2000, 40) }

                    val decision = decisionOf(scanAndSettle(ownPath).path("analysisId").asText())!!

                    decision.path("choice").isNull shouldBe true
                    decision.path("readName").asText() shouldBe "꼬북칩"
                }
            }
        }

        given("이름 스캔 그림자 판별 — 부르지 않거나 실패하는 경우 응답은 현행 그대로") {
            `when`("imagePath 가 없으면") {
                then("판별을 부르지 않고 decision 도 없다") {
                    val payload = scanAndSettle(null)

                    fake.calls.size shouldBe 0
                    decisionOf(payload.path("analysisId").asText()) shouldBe null
                }
            }

            `when`("판별기가 없으면(플래그 off·키 없음)") {
                then("판별을 부르지 않고 decision 도 없다") {
                    decisions.decider = null
                    try {
                        val payload = scanAndSettle(ownPath)

                        payload.path("state").asText() shouldBe "CANDIDATES"
                        decisionOf(payload.path("analysisId").asText()) shouldBe null
                    } finally {
                        decisions.decider = fake
                    }
                    fake.calls.size shouldBe 0
                }
            }

            `when`("일일 상한을 넘으면") {
                then("부르지 않고 error=budget 을 남긴다") {
                    decisions.dailyBudgetKrw = 0.0
                    try {
                        val payload = scanAndSettle(ownPath)

                        payload.path("state").asText() shouldBe "CANDIDATES"
                        decisionOf(payload.path("analysisId").asText())!!.path("error").asText() shouldBe "budget"
                    } finally {
                        decisions.dailyBudgetKrw = 2000.0
                    }
                    fake.calls.size shouldBe 0
                }
            }

            `when`("본인이 올리지 않은 가공식품 스캔 경로면") {
                then("부르지 않고 error=image 를 남긴다") {
                    val payload = scanAndSettle("local/images/packaged-scans/2026/10/999_other.jpg")

                    payload.path("state").asText() shouldBe "CANDIDATES"
                    decisionOf(payload.path("analysisId").asText())!!.path("error").asText() shouldBe "image"
                    fake.calls.size shouldBe 0
                }
            }

            `when`("본인이 올렸어도 메뉴판 스캔 목적 경로면") {
                then("부르지 않고 error=purpose 를 남긴다") {
                    val payload = scanAndSettle(ownMenuScanPath)

                    payload.path("state").asText() shouldBe "CANDIDATES"
                    decisionOf(payload.path("analysisId").asText())!!.path("error").asText() shouldBe "purpose"
                    fake.calls.size shouldBe 0
                }
            }

            listOf(
                "타임아웃" to ({ throw RuntimeException(SocketTimeoutException("timeout")) } to "timeout"),
                "거절(refusal)" to ({ throw NameDecisionRefused() } to "refusal"),
                "사진을 못 가져옴(CDN 404)" to ({ throw NameDecisionImageUnavailable(RuntimeException("404")) } to "image"),
                "그 밖의 실패" to ({ throw IllegalStateException("boom") } to "error"),
            ).forEach { (label, case) ->
                val (behavior, reason) = case
                `when`("판별이 $label 로 끝나면") {
                    then("응답은 그대로이고 error=$reason 을 남긴다") {
                        fake.behavior = behavior
                        val plain = scanAndSettle(null)

                        val payload = scanAndSettle(ownPath)

                        reportNos(payload) shouldBe reportNos(plain)
                        val decision = decisionOf(payload.path("analysisId").asText())!!
                        decision.path("error").asText() shouldBe reason
                        decision.has("choice") shouldBe false
                        decision.path("model").asText() shouldNotBe ""
                    }
                }
            }
        }
    }
}
