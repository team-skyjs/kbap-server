package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodSameNameGroupTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, maker: String, capacity: String? = null, coverage: String? = null, status: String? = null, raw: String = "옥수수, 바나나"): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, capacity, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, cutout_coverage, cutout_review_status, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '과자', ${q(maker)}, ${q(capacity)}, ${q(raw)}, NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), ${coverage ?: "NULL"}, ${q(status)}, 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun get(path: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get(path) { header("X-API-Version", "3.2"); token?.let { header("Authorization", "Bearer $it") } }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun reportNos(page: JsonNode): List<String> = page.path("items").map { it.path("reportNo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("같은 이름 상품을 대표 1개로 묶기(KB-950)") {
            `when`("용량만 다른 바나나킥 두 행이 있으면(dev 실측 report 2001054954151 140g 0.20 / 199104611019 75g 0.71)") {
                then("검색 결과에 바나나킥은 1건이고, 둘 다 편의점 같은 대표 라인이라 누끼 비율이 높은 75g 이 대표다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")
                    seed("TEST-BM-1", "바나나우유", "테스트유업")

                    val found = reportNos(get("/api/processed-foods?query=바나나"))

                    found.count { it == "2001054954151" || it == "199104611019" } shouldBe 1
                    found.contains("199104611019") shouldBe true
                    found.contains("TEST-BM-1") shouldBe true
                }
            }
            `when`("검색어 없는 편의점 목록에서도") {
                then("바나나킥은 1건이고 같은 규칙(승인 → 누끼 비율)으로 대표를 고른다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000", "APPROVED")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    val shown = reportNos(get("/api/processed-foods?size=50"))

                    shown.count { it == "2001054954151" || it == "199104611019" } shouldBe 1
                    shown.contains("2001054954151") shouldBe true
                }
            }
            `when`("묶음 안에 누끼 승인(APPROVED) 행이 있으면") {
                then("누끼 비율보다 승인이 먼저다") {
                    seed("2001054954151", "바나나킥", "농심", "140g", "0.2000", "APPROVED")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    reportNos(get("/api/processed-foods?query=바나나킥")) shouldBe listOf("2001054954151")
                }
            }
            `when`("묶음 안에 편의점 대표 라인 밖 행이 있으면") {
                then("누끼 비율이 더 높아도 대표 라인 안 행이 먼저다") {
                    seed("TEST-BK-OUT", "바나나킥", "농심", "45g", "0.9900")
                    seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    reportNos(get("/api/processed-foods?query=바나나킥")) shouldBe listOf("199104611019")
                }
            }
            `when`("조건이 모두 같으면") {
                then("id 가 작은 쪽이 대표다") {
                    val first = seed("TEST-SAME-1", "같은이름과자", "테스트제과", "50g")
                    seed("TEST-SAME-2", "같은이름과자", "테스트제과", "100g")

                    get("/api/processed-foods?query=같은이름과자").path("items").map { it.path("id").asLong() } shouldBe listOf(first)
                }
            }
            `when`("묶음이 여럿인 검색을 한 건씩 커서로 넘기면") {
                then("경계에서 같은 묶음이 두 번 나오거나 빠지지 않는다") {
                    (1..4).forEach { n ->
                        seed("TEST-PG-$n-A", "페이지과자$n", "테스트제과", "50g")
                        seed("TEST-PG-$n-B", "페이지과자$n", "테스트제과", "100g")
                    }

                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    do {
                        val page = get("/api/processed-foods?query=페이지과자&size=1" + (cursor?.let { "&cursor=$it" } ?: ""))
                        seen += page.path("items").map { it.path("productName").asText() }
                        cursor = page.path("nextCursor").takeUnless { it.isNull || it.isMissingNode }?.asText()
                    } while (cursor != null)

                    seen shouldContainExactlyInAnyOrder (1..4).map { "페이지과자$it" }
                }
            }
            `when`("안전도 필터를 쓰면") {
                then("대표가 아니라 필터를 통과한 묶음 행 중에서 대표를 고른다 — 대표가 걸러져도 통과한 형제가 남는다") {
                    seed("TEST-VF-A", "필터과자", "테스트제과", "50g", "0.9000", "APPROVED", raw = "소맥분(밀), 땅콩")
                    seed("TEST-VF-B", "필터과자", "테스트제과", "100g", "0.1000", raw = "소맥분(밀), 설탕")
                    FoodTestSeed.seedMemberAvoiding(dataSource, 95001L, "PEANUT")
                    val token = tokenIssuer.issueAccessToken(95001L, MemberRole.USER)

                    reportNos(get("/api/processed-foods?query=필터과자&verdict=SAFE", token)) shouldBe listOf("TEST-VF-B")
                    reportNos(get("/api/processed-foods?query=필터과자", token)) shouldBe listOf("TEST-VF-A")
                }
            }
            `when`("묶음의 한 상품 상세를 보면") {
                then("같은 묶음 형제 목록(id·용량)이 실린다 — 자기 자신 포함, id 순") {
                    val big = seed("2001054954151", "바나나킥", "농심", "140g", "0.2000")
                    val small = seed("199104611019", "바나나킥", "농심", "75g", "0.7100")

                    val siblings = get("/api/processed-foods/$small").path("sameNameProducts")

                    siblings.map { it.path("id").asLong() to it.path("capacity").asText() } shouldBe listOf(big to "140g", small to "75g").sortedBy { it.first }
                }
            }
        }
    }
}
