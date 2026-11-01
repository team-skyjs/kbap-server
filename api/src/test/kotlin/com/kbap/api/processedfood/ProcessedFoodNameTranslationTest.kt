package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.translation.FakeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.domain.translation.ContentTranslationJpaRepository
import com.kbap.common.domain.translation.model.TranslationTargetType
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
class ProcessedFoodNameTranslationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var translations: ProcessedFoodTranslationService
    @Autowired private lateinit var rows: ContentTranslationJpaRepository
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '과자', '해태제과', '소맥분(밀), 설탕', '밀 함유', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { r -> r.next(); r.getLong(1) } } }
        }
        fun staleName(id: Long) = execute(
            "INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at) " +
                "VALUES ('PF_NAME', $id, 'en', 'stale', 'Premonition', NULL, 'ACTIVE', NOW(6), NOW(6))",
        )
        fun storedName(id: Long, language: String): String? = rows.findByTargetTypeAndTargetIdAndLanguage(TranslationTargetType.PF_NAME, id, language)?.translatedText
        fun regenerate(target: String, body: Map<String, Any?>, expected: Int = 200): JsonNode = mapper.readTree(
            mockMvc.post("/api/admin/processed-foods/translations/names/regenerate") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                header("X-Target-Environment", target)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )

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
            translations.dailyBudgetKrw = 5000.0
        }

        given("가공식품 이름 번역 규칙(KB-953)") {
            `when`("가공식품 상세를 영어로 보면") {
                then("이름은 이름 전용 번역(브랜드 로마자 규칙)으로, 원재료·알레르기는 기존 표시 번역으로 간다") {
                    val id = seed("NT-1", "예감")

                    mapper.readTree(
                        mockMvc.get("/api/processed-foods/$id?lang=en") { header("X-API-Version", "3.2") }
                            .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    )
                    translations.awaitIdle()

                    translator.nameCalls shouldBe listOf("예감" to LanguageCode.EN)
                    translator.calls.map { it.first }.contains("소맥분(밀), 설탕") shouldBe true
                }
            }
        }

        given("가공식품 이름 번역 재생성 어드민 API(KB-953)") {
            `when`("dryRun 이면") {
                then("저장된 PF_NAME 행 수와 비용 추정만 주고 번역을 부르지 않는다") {
                    val id = seed("NT-2", "예감")
                    staleName(id)

                    val p = regenerate("dev", mapOf("dryRun" to true)).path("payload")

                    p.path("total").asLong() shouldBe 1
                    p.path("regenerated").asInt() shouldBe 0
                    (p.path("estimatedTotalKrw").asDouble() > 0) shouldBe true
                    translator.calls.size shouldBe 0
                    storedName(id, "en") shouldBe "Premonition"
                }
            }
            `when`("실행하면") {
                then("이름 전용 번역으로 다시 번역해 덮어쓰고 전·후 표본을 준다") {
                    val id = seed("NT-3", "예감")
                    staleName(id)

                    val p = regenerate("dev", mapOf("dryRun" to false, "limit" to 20)).path("payload")

                    p.path("regenerated").asInt() shouldBe 1
                    p.path("nextAfterId").isNull shouldBe true
                    p.path("samples")[0].path("before").asText() shouldBe "Premonition"
                    p.path("samples")[0].path("after").asText() shouldBe "[en] 예감"
                    translator.nameCalls shouldBe listOf("예감" to LanguageCode.EN)
                    storedName(id, "en") shouldBe "[en] 예감"
                }
            }
            `when`("하루 번역 예산이 중간에 다 되면 — Codex P2(#519)") {
                then("그 행에서 멈추고 budgetExhausted 와 재시작 커서를 줘서 막힌 행을 건너뛰지 않는다") {
                    val first = seed("NT-4", "예감")
                    val second = seed("NT-5", "자가비")
                    staleName(first)
                    staleName(second)
                    translations.dailyBudgetKrw = 0.5

                    val p = regenerate("dev", mapOf("dryRun" to false, "limit" to 20)).path("payload")

                    p.path("regenerated").asInt() shouldBe 1
                    p.path("budgetExhausted").asBoolean() shouldBe true
                    storedName(second, "en") shouldBe "Premonition"
                    val restart = p.path("nextAfterId").asLong()

                    translations.dailyBudgetKrw = 5000.0
                    redis.delete("pf-translate:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
                    val resumed = regenerate("dev", mapOf("dryRun" to false, "limit" to 20, "afterId" to restart)).path("payload")
                    resumed.path("regenerated").asInt() shouldBe 1
                    resumed.path("budgetExhausted").asBoolean() shouldBe false
                    storedName(second, "en") shouldBe "[en] 자가비"
                }
            }
            `when`("요청 환경이 서버 환경과 다르면") {
                then("409 PFOOD-012 이고 번역하지 않는다") {
                    regenerate("prod", mapOf("dryRun" to false), expected = 409).path("code").asText() shouldBe "PFOOD-012"
                    translator.calls.size shouldBe 0
                }
            }
        }
    }
}
