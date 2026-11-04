package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodDetailTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun idOf(reportNo: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { r -> r.next(); r.getLong(1) } } }
        fun seed(reportNo: String, name: String, status: String, extra: String = "") {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, capacity, image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${name.replace(" ", "")}', '과자', '쑥제과(주)', '120g', 'http://www.haccp.or.kr/fresh/prdimg/$reportNo.jpg', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), '$status', NOW(6), NOW(6))",
            )
            if (extra.isNotEmpty()) execute("UPDATE processed_food_product SET $extra WHERE report_no = '$reportNo'")
        }
        fun get(id: Long, expected: Int = 200, environment: String = "dev"): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/processed-foods/$id") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                header("X-Target-Environment", environment)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("어드민 가공식품 단건 조회 — S-963") {
            `when`("ACTIVE 상품이면") {
                then("상태·kcal·대표 사진(승인 누끼 우선)·누끼 검수 상태를 준다") {
                    seed("AD-1", "쑥과자", "ACTIVE", "kcal_total = 480, cutout_image_url = 'https://cdn.test/c.png', cutout_review_status = 'APPROVED'")

                    get(idOf("AD-1")).path("payload").let {
                        it.path("productName").asText() shouldBe "쑥과자"
                        it.path("normalizedName").asText() shouldBe "쑥과자"
                        it.path("source").asText() shouldBe "HACCP"
                        it.path("status").asText() shouldBe "ACTIVE"
                        it.path("kcalTotal").asInt() shouldBe 480
                        it.path("capacity").asText() shouldBe "120g"
                        it.path("manufacturer").asText() shouldBe "쑥제과(주)"
                        it.path("imageUrl").asText() shouldBe "https://cdn.test/c.png"
                        it.path("cutoutReviewStatus").asText() shouldBe "APPROVED"
                    }
                }
            }

            `when`("숨긴(DELETED) 상품이면") {
                then("앱에는 안 보여도 어드민은 조회되고, 누끼가 없으면 원본(https)을 대표로 준다") {
                    seed("AD-2", "건강한우리쑥이야기", "DELETED")

                    get(idOf("AD-2")).path("payload").let {
                        it.path("status").asText() shouldBe "DELETED"
                        it.path("kcalTotal").isNull shouldBe true
                        it.path("imageUrl").asText() shouldBe "https://www.haccp.or.kr/fresh/prdimg/AD-2.jpg"
                        it.path("cutoutReviewStatus").isNull shouldBe true
                    }
                }
            }

            `when`("없는 id 거나 환경이 다르면") {
                then("404 PFOOD-001 · 409 PFOOD-012 다") {
                    seed("AD-1", "쑥과자", "ACTIVE")
                    get(Long.MAX_VALUE, 404).path("code").asText() shouldBe "PFOOD-001"
                    get(idOf("AD-1"), 409, environment = "prod").path("code").asText() shouldBe "PFOOD-012"
                }
            }
        }
    }
}
