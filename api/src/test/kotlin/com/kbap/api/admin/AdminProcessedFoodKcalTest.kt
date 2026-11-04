package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodKcalTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun idOf(reportNo: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { r -> r.next(); r.getLong(1) } } }
        fun seed(reportNo: String, name: String, capacity: String?, kcal: Int? = null): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, capacity, raw_materials, source, content_hash, collected_at, last_seen_at, kcal_total, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '유탕면', '농심', ${q(capacity)}, '소맥분', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), ${kcal ?: "NULL"}, 'ACTIVE', NOW(6), NOW(6))",
            )
            return idOf(reportNo)
        }
        fun admin(request: org.springframework.test.web.servlet.MockHttpServletRequestDsl, body: Any) {
            request.header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
            request.header("X-API-Version", "1.0")
            request.contentType = MediaType.APPLICATION_JSON
            request.content = mapper.writeValueAsString(body)
        }
        fun read(result: ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))
        fun detail(id: Long): JsonNode = read(mockMvc.get("/api/processed-foods/$id") { header("X-API-Version", "3.2") }, 200).path("payload")
        fun setKcal(id: Long, kcal: Int?, expected: Int = 200) = read(mockMvc.patch("/api/admin/processed-foods/$id/kcal") { admin(this, mapOf("kcalTotal" to kcal)) }, expected)
        val manual = mapOf("productName" to "칼로리라면", "manufacturer" to "㈜테스트", "foodType" to "유탕면", "capacity" to "120g", "rawMaterials" to "소맥분")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 칼로리 — S-957") {
            `when`("수동 등록·정정에 kcalTotal 을 주면") {
                then("상세 kcal 에 그 값이 나오고, 정정에서 빼면 지워지며, 음수는 400 COMMON-002 다") {
                    val created = read(mockMvc.post("/api/admin/processed-foods/manual") { admin(this, manual + ("kcalTotal" to 520)) }, 201).path("payload")
                    val id = created.path("id").asLong()
                    created.path("kcal").asInt() shouldBe 520
                    detail(id).path("kcal").asInt() shouldBe 520

                    read(mockMvc.patch("/api/admin/processed-foods/manual/$id") { admin(this, manual) }, 200)
                    detail(id).path("kcal").isNull shouldBe true

                    read(mockMvc.post("/api/admin/processed-foods/manual") { admin(this, manual + ("kcalTotal" to -1)) }, 400).path("code").asText() shouldBe "COMMON-002"
                }
            }

            `when`("HACCP 상품에 어드민 kcal 입력을 하면") {
                then("상세 kcal 에 나오고 null 로 지우며, 음수·없는 상품은 400 COMMON-002 · 404 PFOOD-001 이다") {
                    val id = seed("KC-1", "칼로리컵", "65g")
                    detail(id).path("kcal").isNull shouldBe true

                    setKcal(id, 285).path("payload").path("kcalTotal").asInt() shouldBe 285
                    detail(id).path("kcal").asInt() shouldBe 285
                    setKcal(id, null)
                    detail(id).path("kcal").isNull shouldBe true

                    setKcal(id, -5, 400).path("code").asText() shouldBe "COMMON-002"
                    setKcal(Long.MAX_VALUE, 100, 404).path("code").asText() shouldBe "PFOOD-001"
                }
            }

            `when`("용량이 다른 상품이 한 묶음이면") {
                then("묶음 안 (용량, kcal) 이 둘 이상으로 갈리므로 kcal 을 보이지 않는다") {
                    val bag = seed("KC-1", "칼로리라면", "120g", 500)
                    val cup = seed("KC-2", "칼로리라면컵", "65g", 285)

                    detail(bag).path("kcal").isNull shouldBe true
                    detail(cup).path("kcal").isNull shouldBe true
                }
            }

            `when`("묶음 안 상품이 모두 같은 용량·같은 kcal 이면") {
                then("그 kcal 을 보인다") {
                    val a = seed("KC-1", "칼로리라면", "120g", 500)
                    seed("KC-2", "칼로리라면 120g", "120g", 500)

                    detail(a).path("kcal").asInt() shouldBe 500
                }
            }
        }
    }
}
