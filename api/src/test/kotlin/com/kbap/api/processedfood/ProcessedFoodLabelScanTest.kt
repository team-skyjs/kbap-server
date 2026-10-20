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
        fun label(token: String, analysisId: String?, vararg pages: String, expected: Int = 200, clientRequestId: String? = UUID.randomUUID().toString().takeIf { analysisId == null }) =
            post("/api/processed-foods/scan/label", mapOf("analysisId" to analysisId, "clientRequestId" to clientRequestId, "pages" to pages.map { mapOf("ocrText" to it) }, "lang" to "ko"), token, expected)
        fun usageCountOf(memberId: Long) = long("SELECT COUNT(*) FROM scan_usage u JOIN scan_operation o ON o.id = u.operation_id WHERE o.member_id = $memberId")
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

        given("성분표 분석 — 표시란 머리말(KB-841)") {
            `when`("머리말이 붙은 2면 원문을 보내면") {
                then("머리말은 미매핑에 나오지 않고 검출은 그대로다") {
                    val p = label(
                        member(88411L),
                        null,
                        "원재료명 및 함량: 소맥분(밀), 새우 10%, 설탕, 정제수\n식품유형: 과자 내용량: 90g",
                        "알레르기 유발물질: 밀, 새우 함유\n알레르기유발성분: 대두",
                    ).path("payload")
                    p.path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SHRIMP", "SOY")
                    val unmapped = p.path("unmapped").map { it.asText() }
                    unmapped.filter { it.replace(" ", "") in setOf("원재료명및함량", "원재료명", "식품유형", "내용량", "알레르기", "유발물질", "알레르기유발물질", "알레르기유발성분") } shouldBe emptyList()
                    unmapped.contains("정제수") shouldBe true
                }
            }
            `when`("머리말만 있고 원재료 토큰이 없으면") {
                then("머리말은 판독 성공 근거(한글 토큰 수)로 세지 않아 400 PFOOD-006") {
                    label(member(88412L), null, "원재료명: 식품유형\n알레르기 유발물질 함유", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    scanCountOf(88412L) shouldBe 0
                }
            }
        }

        given("성분표 분석 — 새 분석의 clientRequestId 멱등 키(KB-840)") {
            `when`("응답을 못 받은 앱이 같은 키·같은 원문으로 다시 보내면") {
                then("같은 분석이 같은 결과로 돌아오고 차감은 처음 1회뿐이다") {
                    val token = member(88401L, "SEAFOOD")
                    val key = UUID.randomUUID().toString()
                    val first = label(token, null, *shrimpLabel, clientRequestId = key).path("payload")
                    val again = label(token, null, *shrimpLabel, clientRequestId = key).path("payload")
                    again.path("analysisId").asText() shouldBe first.path("analysisId").asText()
                    again.path("detections") shouldBe first.path("detections")
                    again.path("avoidance") shouldBe first.path("avoidance")
                    first.path("charged").asBoolean() shouldBe true
                    again.path("charged").asBoolean() shouldBe false
                    scanCountOf(88401L) shouldBe 1
                    usageCountOf(88401L) shouldBe 1
                }
            }
            `when`("같은 키로 다른 원문을 보내면") {
                then("409 SCAN-005, 추가 차감 없음") {
                    val token = member(88402L)
                    val key = UUID.randomUUID().toString()
                    label(token, null, *shrimpLabel, clientRequestId = key)
                    label(token, null, "원재료명: 정제수, 새우", clientRequestId = key, expected = 409).path("code").asText() shouldBe "SCAN-005"
                    scanCountOf(88402L) shouldBe 1
                }
            }
            `when`("같은 키로 같은 줄을 면 나눔만 달리해 보내면 — Codex P2") {
                then("다른 요청으로 보고 409 SCAN-005") {
                    val token = member(88405L)
                    val key = UUID.randomUUID().toString()
                    label(token, null, *shrimpLabel, clientRequestId = key)
                    label(token, null, shrimpLabel.joinToString("\n"), clientRequestId = key, expected = 409).path("code").asText() shouldBe "SCAN-005"
                    label(token, null, shrimpLabel.joinToString("\u0000"), clientRequestId = key, expected = 409).path("code").asText() shouldBe "SCAN-005"
                    scanCountOf(88405L) shouldBe 1
                }
            }
            `when`("새 분석에 키가 없거나 UUID 가 아니면") {
                then("400 COMMON-002, 차감 없음") {
                    val token = member(88403L)
                    label(token, null, *shrimpLabel, clientRequestId = null, expected = 400).path("code").asText() shouldBe "COMMON-002"
                    label(token, null, *shrimpLabel, clientRequestId = "not-a-uuid", expected = 400).path("code").asText() shouldBe "COMMON-002"
                    scanCountOf(88403L) shouldBe 0
                }
            }
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
            `when`("앞면처럼 표시란·원재료 줄이 하나도 없으면(제품명에 재료 낱말이 있어도)") {
                then("400 PFOOD-006, 차감 0") {
                    val token = member(88308L)
                    label(token, null, "NEW\n새우깡\n90g", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    label(token, null, "농심 새우깡 매운맛", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    scanCountOf(88308L) shouldBe 0
                }
            }
            `when`("'원재료' 줄이 있으면") {
                then("성분표로 보고 읽는다(줄바꿈으로 넘어간 원재료도 함께)") {
                    val token = member(88309L)
                    label(token, null, "원재료명: 새우").path("payload").path("detections").map { it.path("code").asText() } shouldBe listOf("SHRIMP")
                    label(token, null, "원재료명: 밀가루, 백설탕, 쇼트닝,\n새우").path("payload").path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SHRIMP")
                }
            }
            `when`("영양성분표나 머리말 없는 쉼표 목록이면 — 공부 ②") {
                then("성분표 줄('원재료'·알레르기)이 없어 400 PFOOD-006, 차감 0") {
                    val token = member(88311L)
                    label(token, null, "영양정보 총 내용량 90g\n나트륨 200mg, 탄수화물 30g, 당류 10g, 지방 8g, 단백질 3g", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    label(token, null, "밀가루, 백설탕, 쇼트닝, 새우", expected = 400).path("code").asText() shouldBe "PFOOD-006"
                    scanCountOf(88311L) shouldBe 0
                }
            }
            `when`("표시란과 같은 제조시설 문구가 한 줄로 오면 — 공부 B") {
                then("표시란 재료는 모두 검출, 메밀은 함유 가능에만") {
                    val p = label(member(88312L, "SOY", "BUCKWHEAT"), null, "원재료명: 소맥분, 정제수\n알레르기 유발물질: 밀, 대두, 우유, 쇠고기 함유. 이 제품은 메밀을 사용한 제품과 같은 제조시설에서 제조합니다.").path("payload")
                    p.path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SOY", "MILK", "BEEF")
                    p.path("mayContain").map { it.path("code").asText() } shouldBe listOf("BUCKWHEAT")
                    val avoidance = p.path("avoidance").associate { it.path("code").asText() to it.path("match").asText() }
                    avoidance shouldBe mapOf("SOY" to "DETECTED", "BUCKWHEAT" to "UNCONFIRMED")
                }
            }
            `when`("구두점 없는 표시란 다음 줄에 혼입 문구가 오면 — 공부 2R V6") {
                then("밀·대두·쇠고기는 검출, 땅콩만 함유 가능") {
                    val p = label(member(88315L, "SOY", "PEANUT"), null, "원재료명: 소맥분, 정제수\n알레르기 유발물질: 밀, 대두, 쇠고기 함유\n땅콩 혼입 가능").path("payload")
                    p.path("detections").map { it.path("code").asText() }.toSet() shouldBe setOf("WHEAT", "SOY", "BEEF")
                    p.path("mayContain").map { it.path("code").asText() } shouldBe listOf("PEANUT")
                }
            }
            `when`("원재료 줄 다음 줄·다음 면이 함유 가능 문구면 — 공부 2R V1·V4") {
                then("원재료 줄이 함유 가능에 붙어 사라지지 않아 성공한다") {
                    val token = member(88316L)
                    label(token, null, "원재료명: 정제수, 새우\n메밀 혼입 가능").path("payload").path("detections").map { it.path("code").asText() } shouldBe listOf("SHRIMP")
                    label(member(88317L), null, "원재료명: 정제수, 새우", "이 제품은 메밀을 사용한 제품과 같은 제조시설에서 제조합니다").path("payload").path("detections").map { it.path("code").asText() } shouldBe listOf("SHRIMP")
                }
            }
            `when`("면 원소가 null 이면 — Codex P2") {
                then("400(검증)") {
                    post("/api/processed-foods/scan/label", mapOf("analysisId" to null, "pages" to listOf(null), "lang" to "ko"), member(88313L), expected = 400)
                }
            }
            `when`("같은 회원의 새 분석 두 건이 동시에 오면(더블탭) — 공부") {
                then("교착(500) 없이 둘 다 끝나고 차감은 성공한 수만큼이다") {
                    val token = member(88314L)
                    repeat(3) {
                        val start = java.util.concurrent.CountDownLatch(1)
                        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                        val futures = (1..2).map { pool.submit<Int> { start.await(); mockMvc.post("/api/processed-foods/scan/label") {
                            header("X-API-Version", "3.2"); header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("clientRequestId" to UUID.randomUUID().toString(), "pages" to listOf(mapOf("ocrText" to "원재료명: 감자, 식물성유지, 정제소금")), "lang" to "ko"))
                        }.andReturn().response.status } }
                        start.countDown()
                        futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }.all { it == 200 || it == 403 } shouldBe true
                        pool.shutdown()
                    }
                    scanCountOf(88314L) shouldBe 3
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
                    label(token, id, "원재료명: 쌀, 정제소금, 효소").path("payload").path("charged").asBoolean() shouldBe false
                    label(token, id, "원재료명: 옥수수, 정제소금, 효소").path("payload").path("charged").asBoolean() shouldBe false
                    label(token, id, "원재료명: 보리, 정제소금, 효소", expected = 409).path("code").asText() shouldBe "PFOOD-007"
                    scanCountOf(88305L) shouldBe 1
                    post("/api/processed-foods/scan/confirm", mapOf("analysisId" to id, "reportNo" to "PL-1"), token, expected = 409).path("code").asText() shouldBe "PFOOD-004"
                }
            }
            `when`("첫 성공 뒤 30분이 지나 같은 id 로 다시 보내면") {
                then("무료 재호출 창이 끝나 409 PFOOD-007(앱은 새 분석)") {
                    val token = member(88310L)
                    val id = label(token, null, *shrimpLabel).path("payload").path("analysisId").asText()
                    execute("UPDATE scan_operation SET request_document = JSON_SET(request_document, '$.label.firstSucceededAt', '2000-01-01T00:00:00') WHERE request_key = UUID_TO_BIN('$id')")
                    label(token, id, "원재료명: 감자, 식물성유지, 정제소금", expected = 409).path("code").asText() shouldBe "PFOOD-007"
                    scanCountOf(88310L) shouldBe 1
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
