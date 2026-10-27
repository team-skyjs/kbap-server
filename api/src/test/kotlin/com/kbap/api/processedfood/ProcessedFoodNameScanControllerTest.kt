package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodNameScanControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var reservationStore: ScanReservationStore

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

        fun seedProduct(reportNo: String, name: String, manufacturer: String) {
            val normalized = name.replace(Regex("[\\s()·]|오리지널"), "")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, " +
                    "source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                    "('$reportNo', '$name', '$normalized', '과자', '$manufacturer', '소맥분(밀), 새우', '밀, 새우', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
        }

        fun member(id: Long, scanCount: Int = 0): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = $scanCount, scan_unlocked = 0 WHERE id = $id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }

        fun post(path: String, body: Map<String, Any?>, token: String?, expected: Int = 200): JsonNode =
            mapper.readTree(
                mockMvc.post(path) {
                    header("X-API-Version", "3.2")
                    token?.let { header("Authorization", "Bearer $it") }
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(body)
                }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        fun scanName(token: String?, text: String, expected: Int = 200) = post("/api/processed-foods/scan/name", mapOf("ocrText" to text), token, expected)
        fun confirm(token: String?, analysisId: String, reportNo: String?, expected: Int = 200) =
            post("/api/processed-foods/scan/confirm", mapOf("analysisId" to analysisId, "reportNo" to reportNo), token, expected)
        fun scanCountOf(memberId: Long) = long("SELECT scan_count FROM member WHERE id = $memberId")
        fun usageRowsOf(analysisId: String) =
            long("SELECT COUNT(*) FROM scan_usage u JOIN scan_operation o ON o.id = u.operation_id WHERE o.request_key = UUID_TO_BIN('$analysisId')")

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            seedProduct("PS-1", "새우깡", "농심_구미")
            seedProduct("PS-2", "새우깡블랙", "농심_구미")
            seedProduct("PS-3", "포카칩오리지널", "오리온_청주")
            seedProduct("PS-DEL", "삭제새우칩", "농심_구미")
            execute("UPDATE processed_food_product SET status = 'DELETED' WHERE report_no = 'PS-DEL'")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("이름 스캔 — POST /api/processed-foods/scan/name") {
            `when`("앞면 OCR 텍스트를 보내면") {
                then("분석 ID 와 후보(≤5)를 주고 상태는 CANDIDATES, 차감은 없다") {
                    val token = member(81101L)
                    val p = scanName(token, "NEW\n농심\n새우깡\n90g").path("payload")
                    p.path("state").asText() shouldBe "CANDIDATES"
                    p.path("analysisId").asText().length shouldBe 36
                    p.path("candidates").map { it.path("productName").asText() } shouldBe listOf("새우깡", "새우깡블랙")
                    p.path("candidates").map { it.path("reportNo").asText() } shouldBe listOf("PS-1", "PS-2")
                    p.has("expiresAt") shouldBe true
                    scanCountOf(81101L) shouldBe 0
                }
            }
            `when`("후보가 하나도 없으면") {
                then("NO_CANDIDATE 와 분석 ID(성분표 이어가기용)를 주고 차감은 없다") {
                    val token = member(81102L)
                    val p = scanName(token, "없는제품명입니다").path("payload")
                    p.path("state").asText() shouldBe "NO_CANDIDATE"
                    p.path("candidates").size() shouldBe 0
                    p.path("analysisId").asText().length shouldBe 36
                    scanCountOf(81102L) shouldBe 0
                }
            }
            `when`("비회원이 부르면") {
                then("401") {
                    scanName(null, "새우깡", expected = 401)
                }
            }
        }

        given("이름 스캔 확정 — POST /api/processed-foods/scan/confirm") {
            `when`("후보 중 하나로 확정하면") {
                then("상세(검색 상세와 같은 응답)와 charged=true, 원장 차감 1행·스캔 횟수 +1") {
                    val token = member(81201L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    val p = confirm(token, id, "PS-1").path("payload")
                    p.path("state").asText() shouldBe "CONFIRMED"
                    p.path("charged").asBoolean() shouldBe true
                    p.path("product").path("reportNo").asText() shouldBe "PS-1"
                    p.path("product").path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SHRIMP")
                    usageRowsOf(id) shouldBe 1
                    scanCountOf(81201L) shouldBe 1
                }
            }
            `when`("같은 분석 ID 를 같은 품목으로 다시 확정하면") {
                then("같은 결과·charged=false — 원장·횟수 그대로(멱등)") {
                    val token = member(81202L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-1")
                    val again = confirm(token, id, "PS-1").path("payload")
                    again.path("charged").asBoolean() shouldBe false
                    again.path("product").path("reportNo").asText() shouldBe "PS-1"
                    usageRowsOf(id) shouldBe 1
                    scanCountOf(81202L) shouldBe 1
                }
            }
            `when`("확정된 분석을 다른 품목이나 해당 없음으로 다시 확정하면") {
                then("409 PFOOD-004") {
                    val token = member(81203L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-1")
                    confirm(token, id, "PS-2", expected = 409).path("code").asText() shouldBe "PFOOD-004"
                    confirm(token, id, null, expected = 409).path("code").asText() shouldBe "PFOOD-004"
                }
            }
            `when`("해당 없음(reportNo 없음)으로 확정하면") {
                then("REJECTED·차감 0, 이후 품목 확정은 409") {
                    val token = member(81204L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    val p = confirm(token, id, null).path("payload")
                    p.path("state").asText() shouldBe "REJECTED"
                    p.path("charged").asBoolean() shouldBe false
                    p.path("product").isNull shouldBe true
                    scanCountOf(81204L) shouldBe 0
                    confirm(token, id, "PS-1", expected = 409).path("code").asText() shouldBe "PFOOD-004"
                }
            }
            `when`("후보 밖 품목으로 확정하면") {
                then("허용하고 원장 문서에 후보 밖 선택을 남긴다") {
                    val token = member(81205L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-3").path("payload").path("product").path("reportNo").asText() shouldBe "PS-3"
                    long("SELECT JSON_EXTRACT(request_document, '$.selectedOutsideCandidates') = TRUE FROM scan_operation WHERE request_key = UUID_TO_BIN('$id')") shouldBe 1
                }
            }
            `when`("없는·남의·만료된 분석 ID 이면") {
                then("404 PFOOD-003") {
                    val owner = member(81206L)
                    val other = member(81207L)
                    val id = scanName(owner, "새우깡").path("payload").path("analysisId").asText()
                    confirm(other, id, "PS-1", expected = 404).path("code").asText() shouldBe "PFOOD-003"
                    confirm(owner, "00000000-0000-0000-0000-000000000000", "PS-1", expected = 404).path("code").asText() shouldBe "PFOOD-003"
                    execute("UPDATE scan_operation SET expires_at = expires_at - INTERVAL 31 MINUTE WHERE request_key = UUID_TO_BIN('$id')")
                    confirm(owner, id, "PS-1", expected = 404).path("code").asText() shouldBe "PFOOD-003"
                }
            }
            `when`("무료 스캔 횟수를 다 쓴 회원이 확정하면") {
                then("403 SCAN-004, 차감 없음") {
                    val token = member(81208L, scanCount = Member.FREE_SCAN_LIMIT)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-1", expected = 403).path("code").asText() shouldBe "SCAN-004"
                    usageRowsOf(id) shouldBe 0
                    scanCountOf(81208L) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                }
            }
            `when`("무료 1회 남은 회원이 서로 다른 분석 두 개를 동시에 확정하면 — 한도 우회 방지(보안 리뷰)") {
                then("하나만 차감되고 다른 하나는 그 차감이 연 무료 창(KB-876) 안이라 차감 0 — 횟수는 한도를 넘지 않는다") {
                    val token = member(81210L, scanCount = Member.FREE_SCAN_LIMIT - 1)
                    val ids = (1..2).map { scanName(token, "새우깡").path("payload").path("analysisId").asText() }
                    val pool = Executors.newFixedThreadPool(2)
                    val start = CountDownLatch(1)
                    val statuses = ids.map { id ->
                        pool.submit<Int> {
                            start.await()
                            mockMvc.post("/api/processed-foods/scan/confirm") {
                                header("X-API-Version", "3.2")
                                header("Authorization", "Bearer $token")
                                contentType = MediaType.APPLICATION_JSON
                                content = mapper.writeValueAsString(mapOf("analysisId" to id, "reportNo" to "PS-1"))
                            }.andReturn().response.status
                        }
                    }
                    start.countDown()
                    val codes = statuses.map { it.get(30, TimeUnit.SECONDS) }
                    pool.shutdown()
                    codes shouldContainExactlyInAnyOrder listOf(200, 200)
                    scanCountOf(81210L) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                    ids.sumOf { usageRowsOf(it) } shouldBe 2
                }
            }
            `when`("남은 1회를 메뉴판 스캔 V2 가 예약 중일 때 이름 확정을 하면 — 공부 ①(같은 예약 저장소)") {
                then("403 SCAN-004 — 예약이 풀리면 확정된다") {
                    val token = member(81211L, scanCount = Member.FREE_SCAN_LIMIT - 1)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    reservationStore.reserve(81211L, "menu-v2-in-flight", Member.FREE_SCAN_LIMIT - 1, Member.FREE_SCAN_LIMIT) shouldBe ScanReservationResult.RESERVED
                    try {
                        confirm(token, id, "PS-1", expected = 403).path("code").asText() shouldBe "SCAN-004"
                        usageRowsOf(id) shouldBe 0
                    } finally {
                        reservationStore.release(81211L, "menu-v2-in-flight")
                    }
                    confirm(token, id, "PS-1").path("payload").path("charged").asBoolean() shouldBe true
                    scanCountOf(81211L) shouldBe Member.FREE_SCAN_LIMIT.toLong()
                }
            }
            `when`("확정 뒤 그 품목이 생산종료 기준을 넘긴 다음 같은 품목으로 재확정하면 — 공부 ②·Codex P2") {
                then("생산종료 검사 없이 같은 결과를 준다(charged=false)") {
                    val token = member(81212L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-2")
                    execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990101, 0, 'processedFoodMirrorJob', 'ps-990101')")
                    execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990101, 0, 990101, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
                    execute("UPDATE processed_food_product SET last_seen_at = last_seen_at - INTERVAL 10 DAY WHERE report_no = 'PS-2'")
                    try {
                        val again = confirm(token, id, "PS-2").path("payload")
                        again.path("charged").asBoolean() shouldBe false
                        again.path("product").path("reportNo").asText() shouldBe "PS-2"
                    } finally {
                        execute("UPDATE processed_food_product SET last_seen_at = last_seen_at + INTERVAL 10 DAY WHERE report_no = 'PS-2'")
                        execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990101")
                        execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990101")
                    }
                }
            }
            `when`("삭제(DELETED)된 품목으로 확정하면 — 공부 ③") {
                then("404 PFOOD-001, 차감 없음") {
                    val token = member(81213L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    confirm(token, id, "PS-DEL", expected = 404).path("code").asText() shouldBe "PFOOD-001"
                    usageRowsOf(id) shouldBe 0
                }
            }
            `when`("같은 분석 ID 확정이 동시에 두 번 오면") {
                then("한 번만 차감된다 — 원장 1행·횟수 +1, 두 응답 모두 같은 품목") {
                    val token = member(81209L)
                    val id = scanName(token, "새우깡").path("payload").path("analysisId").asText()
                    val pool = Executors.newFixedThreadPool(2)
                    val start = CountDownLatch(1)
                    val results = (1..2).map {
                        pool.submit<JsonNode> {
                            start.await()
                            confirm(token, id, "PS-1").path("payload")
                        }
                    }
                    start.countDown()
                    val payloads = results.map { it.get(30, TimeUnit.SECONDS) }
                    pool.shutdown()
                    payloads.map { it.path("product").path("reportNo").asText() }.toSet() shouldBe setOf("PS-1")
                    payloads.map { it.path("charged").asBoolean() } shouldContainExactlyInAnyOrder listOf(true, false)
                    usageRowsOf(id) shouldBe 1
                    scanCountOf(81209L) shouldBe 1
                }
            }
        }
    }
}
