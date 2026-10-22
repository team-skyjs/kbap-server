package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodFeaturedRankingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        val binggrae = "㈜빙그레,F1:경기도 남양주시 도농동 344-3,F2:경상남도 김해시 한림면 병동리 1078-1"

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String, manufacturer: String, seenHoursAgo: Int = 0) {
            fun q(v: String) = "'" + v.replace("'", "''") + "'"
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '가공유', ${q(manufacturer)}, 'HACCP', REPEAT('a', 64), NOW(6), " +
                    "NOW(6) - INTERVAL $seenHoursAgo HOUR, 'ACTIVE', NOW(6), NOW(6))",
            )
        }
        fun seedBananaShelf() {
            seed("BM-1", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 30)
            seed("BM-2", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 2)
            seed("BM-3", "빙그레 바나나맛 우유", "㈜빙그레,경상남도 김해시 한림면 병동리 1078-1", seenHoursAgo = 10)
            listOf("까삭 바나나맛", "제티 바나나맛", "컵케익바나나맛", "타요쌀눈 바나나맛", "현미스낵바나나맛", "초코파이 정 바나나맛").forEachIndexed { i, name ->
                seed("BX-$i", name, "㈜오리온 제${i}공장")
            }
        }
        fun member(id: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = $id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun scan(token: String, text: String): JsonNode = mapper.readTree(
            mockMvc.post("/api/processed-foods/scan/name") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("ocrText" to text))
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun search(query: String): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods") {
                header("X-API-Version", "3.2")
                param("query", query)
                param("size", "30")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun boostedOf(analysisId: String): List<String> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT JSON_EXTRACT(request_document, '$.boostedReportNos') FROM scan_operation WHERE request_key = UUID_TO_BIN('$analysisId')").use { r ->
                    r.next(); r.getString(1)?.let { mapper.readValue(it, List::class.java).map(Any?::toString) } ?: emptyList()
                }
            }
        }
        fun reportNos(node: JsonNode): List<String> = node.map { it.path("reportNo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 이름 스캔 대표 상품 우선(KB-858)") {
            `when`("흘림 로고가 깨지고 '바나나맛'만 읽힌 OCR 을 스캔하면(10/22 실기)") {
                then("빙그레바나나맛우유가 1위이고, 공장별 3행은 가장 최근에 본 1칸으로 묶이며, 올린 품목이 기록에 남는다") {
                    seedBananaShelf()
                    val p = scan(member(85801L), "SrcafA\n바나나맛")
                    val candidates = p.path("candidates")
                    candidates[0].path("reportNo").asText() shouldBe "BM-2"
                    reportNos(candidates).count { it.startsWith("BM-") } shouldBe 1
                    candidates.size() shouldBe 7
                    boostedOf(p.path("analysisId").asText()) shouldBe listOf("BM-2")
                }
            }
            `when`("OCR 에 다른 제조사(매일)가 보이면") {
                then("대표 상품을 올리지 않고 기존 순위대로다") {
                    seedBananaShelf()
                    seed("MI-1", "매일 바나나맛 우유", "매일유업㈜ 광주공장")
                    val p = scan(member(85802L), "매일 바나나맛 우유")
                    p.path("candidates")[0].path("reportNo").asText() shouldBe "MI-1"
                    boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                }
            }
            `when`("대표 상품이 생산종료로 판정되면") {
                then("올리지 않는다") {
                    seed("BM-OLD", "빙그레 바나나맛 우유", binggrae, seenHoursAgo = 24 * 10)
                    seed("BX-A", "까삭 바나나맛", "㈜오리온")
                    execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990301")
                    execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990301")
                    execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990301, 0, 'processedFoodMirrorJob', 'fr-990301')")
                    execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990301, 0, 990301, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
                    try {
                        val p = scan(member(85803L), "SrcafA\n바나나맛")
                        reportNos(p.path("candidates")) shouldBe listOf("BX-A")
                        boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                    } finally {
                        execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990301")
                        execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990301")
                    }
                }
            }
            `when`("별칭이 걸리지 않는 OCR 이면") {
                then("기존 순위 그대로다(정확 → 앞 일치 → 포함, 같은 등급은 짧은 이름 → id)") {
                    seed("SR-1", "왕새우칩", "㈜농심")
                    seed("SR-2", "새우깡블랙", "㈜농심")
                    seed("SR-3", "새우깡", "㈜농심")
                    seed("SR-4", "매운새우깡", "㈜농심")
                    val p = scan(member(85804L), "새우깡")
                    reportNos(p.path("candidates")) shouldBe listOf("SR-3", "SR-2", "SR-4")
                    boostedOf(p.path("analysisId").asText()) shouldBe emptyList()
                }
            }
            `when`("맞는 제품이 30개를 넘으면") {
                then("후보를 30개까지 한 번에 준다") {
                    (1..40).forEach { seed("MANY-$it", "새우스낵$it", "㈜농심") }
                    scan(member(85805L), "새우스낵").path("candidates").size() shouldBe 30
                }
            }
        }

        given("가공식품 탭 검색 대표 상품 우선(KB-858)") {
            `when`("'바나나맛'으로 검색하면") {
                then("빙그레바나나맛우유가 1위, 공장별 3행은 1칸") {
                    seedBananaShelf()
                    val items = search("바나나맛").path("items")
                    items[0].path("reportNo").asText() shouldBe "BM-2"
                    reportNos(items).count { it.startsWith("BM-") } shouldBe 1
                }
            }
            `when`("별칭이 걸리지 않는 검색어면") {
                then("기존 순위 그대로다") {
                    seed("SR-1", "왕새우칩", "㈜농심")
                    seed("SR-2", "새우깡블랙", "㈜농심")
                    seed("SR-3", "새우깡", "㈜농심")
                    reportNos(search("새우깡").path("items")) shouldBe listOf("SR-3", "SR-2")
                }
            }
        }
    }
}
