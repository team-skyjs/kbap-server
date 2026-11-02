package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodManualTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var translator: com.kbap.api.translation.FakeTextTranslator
    @Autowired private lateinit var translations: com.kbap.api.processedfood.ProcessedFoodTranslationService

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun admin() = "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}"
        val wasabi = mapOf(
            "productName" to "와사비맛 아몬드",
            "manufacturer" to "㈜바프",
            "foodType" to "땅콩 또는 견과류가공품",
            "capacity" to "30g",
            "rawMaterials" to "아몬드(미국산), 와사비시즈닝, 설탕",
            "allergens" to "아몬드, 대두, 밀 함유",
        )
        fun create(body: Map<String, Any?>, expected: Int = 201): JsonNode = mapper.readTree(
            mockMvc.post("/api/admin/processed-foods/manual") {
                header("Authorization", admin()); header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun update(id: Long, body: Map<String, Any?>, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.patch("/api/admin/processed-foods/manual/$id") {
                header("Authorization", admin()); header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun get(path: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get(path) { header("X-API-Version", "3.2"); token?.let { header("Authorization", "Bearer $it") } }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 수동 등록(KB-952)") {
            `when`("HACCP 에 없는 상품을 실물 표시대로 등록하면") {
                then("MANUAL 행이 M 접두 순번 키로 생기고, 판정·검색이 바로 동작한다") {
                    val created = create(wasabi).path("payload")
                    created.path("reportNo").asText() shouldBe "M000001"
                    created.path("labelSource").asText() shouldBe "DECLARED"
                    create(wasabi + ("productName" to "눈을감자 기본맛") + ("manufacturer" to "오리온")).path("payload").path("reportNo").asText() shouldBe "M000002"

                    val id = created.path("id").asLong()
                    FoodTestSeed.seedMemberAvoiding(dataSource, 95201L, "ALMOND")
                    val token = tokenIssuer.issueAccessToken(95201L, MemberRole.USER)
                    get("/api/processed-foods/$id", token).path("verdict").asText() shouldBe "AVOID"
                    get("/api/processed-foods?query=와사비맛").path("items").map { it.path("reportNo").asText() } shouldBe listOf("M000001")
                }
            }
            `when`("수동 상품에 승인된 누끼가 붙으면") {
                then("이미지 출처 고지 키가 HACCP 가 아니라 수동 등록이다 — 공부 참고(#518)") {
                    val id = create(wasabi).path("payload").path("id").asLong()
                    execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.example.com/cutout/M000001.png', cutout_review_status = 'APPROVED' WHERE id = $id")

                    get("/api/processed-foods/$id").path("image").path("attributionKey").asText() shouldBe "processedFood.image.manual"
                }
            }
            `when`("수동 등록·정정하면 — KB-960") {
                then("이름 번역을 뒤에서 9개 언어로 바로 만든다(후보·상세에서 한국어만 보이지 않게)") {
                    translator.reset()
                    val id = create(wasabi).path("payload").path("id").asLong()
                    translations.awaitIdle()
                    translator.nameCalls.map { it.first }.toSet() shouldBe setOf("와사비맛 아몬드")
                    translator.nameCalls.map { it.second }.toSet() shouldBe LanguageCode.entries.filter { it != LanguageCode.KO }.toSet()

                    translator.reset()
                    update(id, wasabi + ("productName" to "와사비맛 아몬드 대용량"))
                    translations.awaitIdle()
                    translator.nameCalls.map { it.first }.toSet() shouldBe setOf("와사비맛 아몬드 대용량")
                }
                then("고정된 (상품, 언어)는 정정해도 다시 번역하지 않는다 — KB-960 후속") {
                    val id = create(wasabi + ("productName" to "와사비 아몬드 고정")).path("payload").path("id").asLong()
                    translations.awaitIdle()
                    execute(
                        "INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at) " +
                            "VALUES ('PF_NAME_PINNED', $id, 'en', 'pin', 'Wasabi Almond', NULL, 'ACTIVE', NOW(6), NOW(6))",
                    )
                    translator.reset()
                    update(id, wasabi + ("productName" to "와사비 아몬드 고정 대용량"))
                    translations.awaitIdle()
                    translator.nameCalls.map { it.second }.toSet() shouldBe LanguageCode.entries.filter { it != LanguageCode.KO && it != LanguageCode.EN }.toSet()
                }
                then("제조사 원문(㈜오뚜기)이 아니라 표시 제조사 기준으로 접두를 뺀다 — Codex P2(#524)") {
                    translator.reset()
                    create(wasabi + ("productName" to "오뚜기 뿌셔뿌셔 양념치킨맛") + ("manufacturer" to "㈜오뚜기"))
                    translations.awaitIdle()
                    translator.nameCalls.map { it.first }.toSet() shouldBe setOf("뿌셔뿌셔 양념치킨맛")
                }
            }
            `when`("지워진 수동 키가 있어도") {
                then("그 번호를 다시 쓰지 않고 다음 번호를 매긴다") {
                    execute(
                        "INSERT INTO processed_food_product (report_no, product_name, normalized_name, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                            "VALUES ('M000009', '지운상품', '지운상품', '테스트', 'MANUAL', REPEAT('a', 64), NOW(6), NOW(6), 'DELETED', NOW(6), NOW(6))",
                    )
                    create(wasabi).path("payload").path("reportNo").asText() shouldBe "M000010"
                }
            }
            `when`("수동 등록 상품의 오타를 고치면") {
                then("품목보고번호는 그대로이고 이름·파생 정규화 이름이 바뀌어 새 이름으로 검색된다") {
                    val id = create(wasabi + ("productName" to "와사비맞 아몬드")).path("payload").path("id").asLong()

                    val fixed = update(id, wasabi).path("payload")

                    fixed.path("reportNo").asText() shouldBe "M000001"
                    fixed.path("productName").asText() shouldBe "와사비맛 아몬드"
                    get("/api/processed-foods?query=와사비맛").path("items").map { it.path("id").asLong() } shouldBe listOf(id)
                }
            }
            `when`("HACCP 행을 고치려 하면") {
                then("409 PFOOD-011 이다") {
                    execute(
                        "INSERT INTO processed_food_product (report_no, product_name, normalized_name, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                            "VALUES ('19700001001', '새우깡', '새우깡', '농심', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                    )
                    val id = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '19700001001'").use { r -> r.next(); r.getLong(1) } } }

                    update(id, wasabi, expected = 409).path("code").asText() shouldBe "PFOOD-011"
                }
            }
            `when`("상품명이나 제조사가 비면") {
                then("400 이다") {
                    create(wasabi - "productName", expected = 400)
                    create(wasabi + ("manufacturer" to " "), expected = 400)
                }
            }
        }
    }
}
