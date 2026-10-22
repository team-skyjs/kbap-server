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
class ProcessedFoodListTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, maker: String, foodType: String, seenHoursAgo: Int = 0, raw: String? = null): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, ${q(foodType)}, ${q(maker)}, ${q(raw)}, 'HACCP', REPEAT('a', 64), NOW(6), " +
                    "NOW(6) - INTERVAL $seenHoursAgo HOUR, 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun review(memberId: Long, productId: Long, rating: Int) = execute(
            "INSERT INTO food_review (member_id, target_type, food_id, processed_food_id, rating, serving_speed_rating, staff_kindness_rating, content, image_refs, status, created_at, updated_at, version) " +
                "VALUES ($memberId, 'PROCESSED_FOOD', NULL, $productId, $rating, 0, 0, '맛있어요', JSON_ARRAY(), 'ACTIVE', NOW(6), NOW(6), 0)",
        )
        fun member(id: Long, vararg avoiding: String): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id, *avoiding)
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun list(query: String, token: String? = null, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods$query") {
                header("X-API-Version", "3.2")
                token?.let { header("Authorization", "Bearer $it") }
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun reportNos(page: JsonNode): List<String> = page.path("payload").path("items").map { it.path("reportNo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990671")
            execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990671")
            execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990671, 0, 'processedFoodMirrorJob', 'ls-990671')")
            execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990671, 0, 990671, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
        }
        afterSpec {
            execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990671")
            execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990671")
        }

        given("가공식품 목록 — 검색어 없이(KB-867)") {
            fun seedShelf() {
                seed("RS-1", "감자칩 소금맛", "㈜스낵", "과자")
                seed("RS-2", "꿀호떡", "㈜베이커리", "빵류", seenHoursAgo = 5)
                seed("RS-3", "꿀호떡", "㈜베이커리", "빵류", seenHoursAgo = 1)
                seed("RS-4", "단종 크래커", "㈜스낵", "과자", seenHoursAgo = 24 * 10)
                seed("FT-2", "신라면", "㈜농심", "유탕면류")
                seed("FT-1", "빙그레 바나나맛 우유", "㈜빙그레", "가공유", raw = "원유(국산) 85%, 정제수, 설탕, 합성향료")
            }
            `when`("열면") {
                then("대표 상품을 카탈로그 순서로 먼저, 그다음 나머지를 같은 제품 한 칸·생산종료 제외로 준다") {
                    seedShelf()
                    reportNos(list("")) shouldBe listOf("FT-1", "FT-2", "RS-1", "RS-3")
                }
            }
            `when`("나머지에 대표 상품의 제조사명+이름 행이 다른 제조사 표기로 있으면") {
                then("대표 상품 칸으로 묶여 따로 나오지 않는다") {
                    seedShelf()
                    seed("RS-5", "농심 신라면", "농심태경(주)", "유탕면류")
                    reportNos(list("?type=RAMEN")) shouldBe listOf("FT-2")
                }
            }
            `when`("커서로 넘기면") {
                then("겹치거나 빠지지 않고 끝에서 hasNext 가 false 다") {
                    seedShelf()
                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    repeat(4) {
                        val page = list("?size=1" + (cursor?.let { "&cursor=$it" } ?: ""))
                        seen += reportNos(page)
                        cursor = page.path("payload").path("nextCursor").takeUnless { it.isNull }?.asText()
                    }
                    seen shouldBe listOf("FT-1", "FT-2", "RS-1", "RS-3")
                    cursor shouldBe null
                }
                then("목록은 검색 커서 상한(10,000)에 걸리지 않는다 — 전체 목록이 1만 칸을 넘어도 끝까지 넘긴다") {
                    seedShelf()
                    val page = list("?cursor=20000").path("payload")
                    page.path("items").size() shouldBe 0
                    page.path("hasNext").asBoolean() shouldBe false
                    list("?cursor=-1", expected = 400)
                    val last = list("?cursor=2147483647").path("payload")
                    last.path("items").size() shouldBe 0
                    last.path("hasNext").asBoolean() shouldBe false
                    last.path("nextCursor").isNull shouldBe true
                }
            }
            `when`("유형 칩으로 거르면") {
                then("대표 상품과 나머지 모두 그 유형만 남는다") {
                    seedShelf()
                    reportNos(list("?type=RAMEN")) shouldBe listOf("FT-2")
                    reportNos(list("?type=DAIRY")) shouldBe listOf("FT-1")
                    reportNos(list("?type=BREAD")) shouldBe listOf("RS-3")
                    reportNos(list("?type=SNACKS")) shouldBe listOf("RS-1")
                    list("?type=UNKNOWN_CHIP", expected = 400)
                }
            }
            `when`("항목을 보면") {
                then("한국어 이름·유형 칩·평점 평균과 수·회원 판정이 실린다") {
                    seedShelf()
                    val bananaId = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = 'FT-1'").use { r -> r.next(); r.getLong(1) } } }
                    FoodTestSeed.seedMemberAvoiding(dataSource, 86711L)
                    FoodTestSeed.seedMemberAvoiding(dataSource, 86712L)
                    review(86711L, bananaId, 5)
                    review(86712L, bananaId, 4)

                    val banana = list("", member(86713L, "MILK")).path("payload").path("items")[0]
                    banana.path("nameKo").asText() shouldBe "빙그레 바나나맛 우유"
                    banana.path("type").asText() shouldBe "DAIRY"
                    banana.path("rating").path("average").asDouble() shouldBe 4.5
                    banana.path("rating").path("count").asLong() shouldBe 2L
                    banana.path("verdict").asText() shouldBe "AVOID"
                    list("").path("payload").path("items")[1].path("rating").path("count").asLong() shouldBe 0L
                }
            }
        }

        given("이름 스캔 후보 평점(KB-867, 디자인 16)") {
            `when`("후보를 받으면") {
                then("후보마다 목록과 같은 rating{average,count} 이 실린다") {
                    val id = seed("NS-1", "꿀호떡", "㈜베이커리", "빵류")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 86721L)
                    FoodTestSeed.seedMemberAvoiding(dataSource, 86722L)
                    review(86721L, id, 5)
                    review(86722L, id, 3)
                    val token = member(86723L)
                    execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = 86723")

                    val candidate = mapper.readTree(
                        mockMvc.post("/api/processed-foods/scan/name") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer $token")
                            contentType = MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("ocrText" to "꿀호떡"))
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("candidates")[0]
                    candidate.path("reportNo").asText() shouldBe "NS-1"
                    candidate.path("rating").path("average").asDouble() shouldBe 4.0
                    candidate.path("rating").path("count").asLong() shouldBe 2L
                }
            }
        }

        given("가공식품 검색 + 유형 칩(KB-867)") {
            `when`("검색어와 유형을 함께 주면") {
                then("검색 결과를 그 유형으로 거른다") {
                    seed("QT-1", "꿀호떡", "㈜베이커리", "빵류")
                    seed("QT-2", "꿀호떡 스낵", "㈜스낵", "과자")
                    reportNos(list("?query=꿀호떡&type=BREAD")) shouldBe listOf("QT-1")
                    reportNos(list("?query=꿀호떡&type=SNACKS")) shouldBe listOf("QT-2")
                }
            }
            `when`("검색어에 맞는 다른 유형 제품이 후보 상한(300)보다 많으면 — Codex P1") {
                then("유형을 색인 단계에서 먼저 걸러 그 유형 제품이 나온다") {
                    (1..301).forEach { seed("CP-%03d".format(it), "공통과자%03d".format(it), "㈜스낵", "과자") }
                    seed("CP-BREAD", "공통빵이름이아주긴베이커리제품", "㈜베이커리", "빵류")
                    reportNos(list("?query=공통&type=BREAD")) shouldBe listOf("CP-BREAD")
                }
            }
            `when`("제조사 검색어로 유형을 거르는데 다른 유형에 이름이 맞는 제품이 있으면 — Codex P1") {
                then("다른 유형 제품이 순위·제조사만 일치 숨김에 끼어들지 않아 그 유형의 제조사 제품이 나온다") {
                    seed("QM-1", "베이커리 감자칩", "㈜스낵", "과자")
                    seed("QM-2", "꿀호떡", "㈜베이커리", "빵류")
                    reportNos(list("?query=베이커리&type=BREAD")) shouldBe listOf("QM-2")
                }
            }
        }
    }
}
