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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodStatusTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        var adminId = 0L

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun string(sql: String): String? = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getString(1) else null } } }

        fun seed(reportNo: String, name: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '$name', '과자', '숨김제과', '쑥, 쌀', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return string("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'")!!.toLong()
        }

        fun read(result: ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))

        fun changeStatus(id: Long, status: String, expected: Int = 200, environment: String = "dev") =
            read(
                mockMvc.patch("/api/admin/processed-foods/$id/status") {
                    header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)}")
                    header("X-API-Version", "1.0")
                    header("X-Target-Environment", environment)
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("status" to status))
                },
                expected,
            )

        fun app(path: String, expected: Int = 200) = read(mockMvc.get(path) { header("X-API-Version", "3.2") }, expected)

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("INSERT IGNORE INTO admin_account (admin_id, admin_pwd, status, created_at, updated_at) VALUES ('status-admin', 'x', 'ACTIVE', NOW(6), NOW(6))")
            adminId = string("SELECT id FROM admin_account WHERE admin_id = 'status-admin'")!!.toLong()
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 숨김·복원 — S-958") {
            `when`("ACTIVE 상품을 DELETED 로 바꾸면") {
                then("상세는 404 PFOOD-001, 검색·목록에서 빠지고, 같은 요청을 다시 보내도 결과가 같다(changed=false)") {
                    val id = seed("HIDE-1", "건강한우리쑥이야기")
                    seed("HIDE-2", "우리쑥떡")
                    app("/api/processed-foods?query=쑥").path("payload").path("items").map { it.path("reportNo").asText() }.toSet() shouldBe setOf("HIDE-1", "HIDE-2")

                    changeStatus(id, "DELETED").path("payload").let {
                        it.path("status").asText() shouldBe "DELETED"
                        it.path("changed").asBoolean() shouldBe true
                    }

                    app("/api/processed-foods/$id", 404).path("code").asText() shouldBe "PFOOD-001"
                    app("/api/processed-foods?query=쑥").path("payload").path("items").map { it.path("reportNo").asText() } shouldBe listOf("HIDE-2")
                    changeStatus(id, "DELETED").path("payload").path("changed").asBoolean() shouldBe false
                    string("SELECT status FROM processed_food_product WHERE id = $id") shouldBe "DELETED"
                }
            }

            `when`("숨긴 상품을 ACTIVE 로 되돌리면") {
                then("상세·검색에 다시 나온다") {
                    val id = seed("HIDE-1", "건강한우리쑥이야기")
                    changeStatus(id, "DELETED")

                    changeStatus(id, "ACTIVE").path("payload").path("changed").asBoolean() shouldBe true

                    app("/api/processed-foods/$id").path("payload").path("reportNo").asText() shouldBe "HIDE-1"
                    app("/api/processed-foods?query=쑥").path("payload").path("items").map { it.path("reportNo").asText() } shouldBe listOf("HIDE-1")
                }
            }

            `when`("대상 환경 헤더가 서버 환경과 다르거나 없는 상품이면") {
                then("409 PFOOD-012 · 404 PFOOD-001 이고 상태는 그대로다") {
                    val id = seed("HIDE-1", "건강한우리쑥이야기")

                    changeStatus(id, "DELETED", 409, environment = "prod").path("code").asText() shouldBe "PFOOD-012"
                    changeStatus(Long.MAX_VALUE, "DELETED", 404).path("code").asText() shouldBe "PFOOD-001"
                    string("SELECT status FROM processed_food_product WHERE id = $id") shouldBe "ACTIVE"
                }
            }
        }
    }
}
