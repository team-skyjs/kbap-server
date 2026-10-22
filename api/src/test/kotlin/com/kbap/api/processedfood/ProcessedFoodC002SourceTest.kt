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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodC002SourceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, manufacturer: String, source: String, seenDaysAgo: Int, raw: String? = null): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '유탕면', ${q(manufacturer)}, ${q(raw)}, ${q(source)}, REPEAT('a', 64), NOW(6), " +
                    "NOW(6) - INTERVAL $seenDaysAgo DAY, 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun member(id: Long): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id)
            execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = $id")
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun scanReportNos(token: String, text: String): List<String> = mapper.readTree(
            mockMvc.post("/api/processed-foods/scan/name") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("ocrText" to text))
            }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("candidates").map { it.path("reportNo").asText() }
        fun searchReportNos(query: String): List<String> = mapper.readTree(
            mockMvc.get("/api/processed-foods") {
                header("X-API-Version", "3.2")
                param("query", query)
            }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("items").map { it.path("reportNo").asText() }
        fun detail(id: Long): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods/$id") { header("X-API-Version", "3.2") }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990402")
            execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990402")
            execute("INSERT INTO BATCH_JOB_INSTANCE (JOB_INSTANCE_ID, VERSION, JOB_NAME, JOB_KEY) VALUES (990402, 0, 'processedFoodMirrorJob', 'c2-990402')")
            execute("INSERT INTO BATCH_JOB_EXECUTION (JOB_EXECUTION_ID, VERSION, JOB_INSTANCE_ID, CREATE_TIME, START_TIME, END_TIME, STATUS, EXIT_CODE, LAST_UPDATED) VALUES (990402, 0, 990402, NOW(6), NOW(6), NOW(6), 'COMPLETED', 'COMPLETED', NOW(6))")
        }
        afterSpec {
            execute("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = 990402")
            execute("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID = 990402")
        }

        given("C002(품목제조보고) 출처 가공식품 행(KB-859)") {
            `when`("마지막 확인이 미러 완료보다 3일 넘게 지났어도") {
                then("생산종료로 보지 않아 이름 스캔·탭 검색 후보에 남는다 — 같은 조건의 HACCP 행은 빠진다") {
                    seed("C2-1", "짜파게티 범벅 감바스", "(주)농심", "C002", seenDaysAgo = 30)
                    seed("HC-1", "짜파게티 범벅 감바스 컵", "(주)농심", "HACCP", seenDaysAgo = 30)

                    val scanned = scanReportNos(member(85901L), "짜파게티 범벅 감바스")
                    scanned shouldContain "C2-1"
                    scanned shouldNotContain "HC-1"
                    val searched = searchReportNos("짜파게티범벅감바스")
                    searched shouldContain "C2-1"
                    searched shouldNotContain "HC-1"
                }
            }
            `when`("상세를 보면") {
                then("생산종료 오류 없이 원재료에서 판독하고, 출처 고지 키는 C002 것이며 이미지는 없다") {
                    val id = seed("C2-2", "앵그리짜파구리", "(주)농심", "C002", seenDaysAgo = 30, raw = "소맥분(밀:호주산), 팜유, 대두유")

                    val response = detail(id)
                    response.path("success").asBoolean() shouldBe true
                    val p = response.path("payload")
                    p.path("labelSource").asText() shouldBe "INGREDIENTS_ONLY"
                    p.path("detections").map { it.path("code").asText() } shouldContain "WHEAT"
                    p.path("source").path("provider").asText() shouldBe "C002"
                    p.path("source").path("attributionKey").asText() shouldBe "processedFood.source.c002"
                    p.path("image").isNull shouldBe true
                }
            }
        }
    }
}
