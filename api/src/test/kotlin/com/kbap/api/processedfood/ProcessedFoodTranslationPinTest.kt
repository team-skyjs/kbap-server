package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.translation.FakeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodTranslationPinTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var translations: ProcessedFoodTranslationService
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun seed(reportNo: String, name: String, maker: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '과자', '$maker', '소맥분(밀), 설탕', '밀 함유', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return long("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'")
        }
        fun admin(path: String, body: Map<String, Any?>, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post("/api/admin/processed-foods/translations$path") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                header("X-Target-Environment", "dev")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun pins(productId: Long): JsonNode = mapper.readTree(
            mockMvc.get("/api/admin/processed-foods/translations/names/overrides?productId=$productId") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun detail(id: Long, lang: String = "en"): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods/$id?lang=$lang") { header("X-API-Version", "3.2") }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun pinnedRows() = long("SELECT COUNT(*) FROM content_translation WHERE target_type IN ('PF_NAME_PINNED', 'PF_MAKER_PINNED')")

        beforeEach {
            translations.awaitIdle()
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            translator.reset()
            translations.dailyBudgetKrw = 5000.0
            redis.delete("pf-translate:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
        }
        afterSpec {
            translations.awaitIdle()
            translator.reset()
        }

        given("가공식품 이름·제조사 번역 고정(KB-960 후속)") {
            `when`("고정값을 dryRun 으로 보내면") {
                then("적용·무변·거절 수와 사유만 주고 아무것도 저장하지 않는다") {
                    val id = seed("PIN-1", "새우깡", "농심")
                    val result = admin(
                        "/names/overrides",
                        mapOf(
                            "dryRun" to true,
                            "items" to listOf(
                                mapOf("productId" to id, "language" to "en", "name" to "Saeukkang"),
                                mapOf("productId" to id, "language" to "ko", "name" to "새우깡"),
                                mapOf("productId" to id, "language" to "xx", "name" to "X"),
                                mapOf("productId" to id, "language" to "ja", "name" to " "),
                                mapOf("productId" to 99_999_999L, "language" to "en", "name" to "Ghost"),
                            ),
                        ),
                    ).path("payload")
                    result.path("applied").asInt() shouldBe 1
                    result.path("unchanged").asInt() shouldBe 0
                    result.path("rejected").asInt() shouldBe 4
                    result.path("rejections").map { it.path("reason").asText() }.toSet() shouldBe setOf("UNSUPPORTED_LANGUAGE", "BLANK", "PRODUCT_NOT_FOUND")
                    pinnedRows() shouldBe 0
                }
            }
            `when`("이름·제조사를 고정하면") {
                then("상세·목록이 고정값을 쓰고 엔진을 부르지 않으며, 같은 값을 다시 보내면 무변이고, 원문이 바뀌어도 고정은 유지된다") {
                    val id = seed("PIN-2", "바프 허니버터아몬드", "바프")
                    admin("/names/overrides", mapOf("dryRun" to false, "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "HBAF Honey Butter Almond")))).path("payload").path("applied").asInt() shouldBe 1
                    admin("/names/overrides", mapOf("dryRun" to false, "kind" to "MAKER", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "HBAF")))).path("payload").path("applied").asInt() shouldBe 1
                    admin("/names/overrides", mapOf("dryRun" to false, "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "HBAF Honey Butter Almond")))).path("payload").path("unchanged").asInt() shouldBe 1

                    val shown = detail(id)
                    shown.path("name").asText() shouldBe "HBAF Honey Butter Almond"
                    shown.path("maker").asText() shouldBe "HBAF"
                    val listed = mapper.readTree(
                        mockMvc.get("/api/processed-foods?query=허니버터아몬드&lang=en") { header("X-API-Version", "3.2") }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("items").first { it.path("id").asLong() == id }
                    listed.path("name").asText() shouldBe "HBAF Honey Butter Almond"
                    listed.path("maker").asText() shouldBe "HBAF"
                    translations.awaitIdle()
                    translator.nameCalls.filter { it.second == LanguageCode.EN } shouldBe emptyList()

                    execute("UPDATE processed_food_product SET product_name = '바프 허니버터 아몬드 대용량' WHERE id = $id")
                    detail(id).path("name").asText() shouldBe "HBAF Honey Butter Almond"

                    val listedPins = pins(id)
                    listedPins.map { it.path("kind").asText() + ":" + it.path("language").asText() + ":" + it.path("name").asText() }.toSet() shouldBe
                        setOf("NAME:en:HBAF Honey Butter Almond", "MAKER:en:HBAF")
                }
            }
            `when`("원 제조사가 없는 상품에 제조사를 고정하면 — Codex P2(#528)") {
                then("상세·목록 모두 고정 제조사를 보여 준다") {
                    val id = seed("PIN-5", "무명과자", "알수없음")
                    admin("/names/overrides", mapOf("dryRun" to false, "kind" to "MAKER", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "HBAF"))))
                    detail(id).path("maker").asText() shouldBe "HBAF"
                    mapper.readTree(
                        mockMvc.get("/api/processed-foods?query=무명과자&lang=en") { header("X-API-Version", "3.2") }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("items").first { it.path("id").asLong() == id }.path("maker").asText() shouldBe "HBAF"
                }
            }
            `when`("이름을 다시 생성하거나 미리 번역하면") {
                then("고정된 상품·언어는 건너뛰어 엔진을 부르지 않고, 고정값도 그대로다") {
                    val id = seed("PIN-3", "예감", "오리온")
                    execute(
                        "INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at) " +
                            "VALUES ('PF_NAME', $id, 'en', 'stale', 'Premonition', NULL, 'ACTIVE', NOW(6), NOW(6))",
                    )
                    admin("/names/overrides", mapOf("dryRun" to false, "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "Yegam"))))
                    admin("/names/regenerate", mapOf("dryRun" to false, "limit" to 10))
                    translations.awaitIdle()
                    translator.nameCalls shouldBe emptyList()
                    detail(id).path("name").asText() shouldBe "Yegam"
                }
            }
            `when`("한 번에 500건을 넘게 보내면") {
                then("400 이다") {
                    val id = seed("PIN-4", "꼬북칩", "오리온")
                    admin("/names/overrides", mapOf("dryRun" to true, "items" to List(501) { mapOf("productId" to id, "language" to "en", "name" to "Turtle Chips") }), expected = 400)
                }
            }
        }
    }
}
