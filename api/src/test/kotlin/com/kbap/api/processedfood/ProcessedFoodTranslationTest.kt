package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.translation.FakeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodTranslationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var translations: ProcessedFoodTranslationService

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun seed(reportNo: String, name: String, maker: String, raw: String?, allergens: String?): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '가공유', ${q(maker)}, ${q(raw)}, ${q(allergens)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun get(path: String): JsonNode = mapper.readTree(
            mockMvc.get(path) { header("X-API-Version", "3.2") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            translator.reset()
            translations.dailyBudgetKrw = 5000.0
        }
        afterSpec {
            translator.reset()
            translations.dailyBudgetKrw = 5000.0
        }

        given("가공식품 번역(KB-868)") {
            `when`("영어로 상세를 처음 보면") {
                then("제품명·원재료·알레르기 표시를 번역해 저장하고, 제조사는 브랜드 사전(Binggrae)으로 LLM 없이 준다") {
                    val id = seed("TR-1", "빙그레 바나나맛 우유", "㈜빙그레", "원유(국산) 85%, 정제수, 설탕", "우유 함유")

                    val p = get("/api/processed-foods/$id?lang=en")
                    p.path("name").asText() shouldBe "[en] 빙그레 바나나맛 우유"
                    p.path("nameKo").asText() shouldBe "빙그레 바나나맛 우유"
                    p.path("maker").asText() shouldBe "Binggrae"
                    p.path("ingredients").path("translated").asText() shouldBe "[en] 원유(국산) 85%, 정제수, 설탕"
                    p.path("ingredients").path("original").asText() shouldBe "원유(국산) 85%, 정제수, 설탕"
                    p.path("allergenStatement").path("translated").asText() shouldBe "[en] 우유 함유"
                    p.path("allergenStatement").path("original").asText() shouldBe "우유 함유"
                    translator.calls.map { it.first }.toSet() shouldBe setOf("빙그레 바나나맛 우유", "원유(국산) 85%, 정제수, 설탕", "우유 함유")
                }
                then("두 번째부터는 저장된 번역을 써서 엔진을 다시 부르지 않는다") {
                    val id = seed("TR-2", "새우깡", "㈜농심", "소맥분(밀), 새우", null)
                    get("/api/processed-foods/$id?lang=ja")
                    val calls = translator.calls.size

                    get("/api/processed-foods/$id?lang=ja").path("name").asText() shouldBe "[ja] 새우깡"
                    translator.calls.size shouldBe calls
                }
            }
            `when`("한국어로 보면") {
                then("번역하지 않고 원문이 그대로다") {
                    val id = seed("TR-3", "꿀호떡", "㈜베이커리", "밀가루, 꿀", null)
                    val p = get("/api/processed-foods/$id?lang=ko")
                    p.path("name").asText() shouldBe "꿀호떡"
                    p.path("ingredients").path("translated").asText() shouldBe "밀가루, 꿀"
                    p.path("allergenStatement").isNull shouldBe true
                    translator.calls.size shouldBe 0
                }
            }
            `when`("브랜드 사전에 없는 제조사면") {
                then("제조사도 LLM 으로 번역한다") {
                    val id = seed("TR-4", "동네과자", "㈜동네식품", "밀가루", null)
                    get("/api/processed-foods/$id?lang=en").path("maker").asText() shouldBe "[en] 동네식품"
                }
            }
            `when`("엔진이 실패하면") {
                then("원문으로 폴백해 200 이다") {
                    translator.reply = { _, _ -> throw IllegalStateException("engine down") }
                    val id = seed("TR-5", "실패과자", "㈜동네식품", "밀가루", null)
                    val p = get("/api/processed-foods/$id?lang=en")
                    p.path("name").asText() shouldBe "실패과자"
                    p.path("ingredients").path("translated").asText() shouldBe "밀가루"
                }
            }
            `when`("오늘 번역 지출이 일일 상한에 닿으면") {
                then("엔진을 부르지 않고 원문으로 폴백한다") {
                    translations.dailyBudgetKrw = 0.0
                    val id = seed("TR-6", "상한과자", "㈜동네식품", "밀가루", null)
                    get("/api/processed-foods/$id?lang=en").path("name").asText() shouldBe "상한과자"
                    translator.calls.size shouldBe 0
                }
            }
            `when`("목록을 영어로 보면") {
                then("저장된 번역만 쓰고(없으면 원문) 빠진 번역은 뒤에서 채워 다음 요청부터 번역이 보인다") {
                    seed("TR-7", "목록과자", "㈜동네식품", "밀가루", null)

                    get("/api/processed-foods?query=목록과자&lang=en").path("items")[0].path("name").asText() shouldBe "목록과자"
                    repeat(50) { if (translator.calls.none { it.first == "목록과자" }) Thread.sleep(100) }
                    translations.awaitIdle()

                    get("/api/processed-foods?query=목록과자&lang=en").path("items")[0].path("name").asText() shouldBe "[en] 목록과자"
                }
            }
            `when`("대표 상품 미리 번역을 돌리면") {
                then("언어마다 대표 상품 이름·원재료를 저장해 목록이 첫 요청부터 번역된다") {
                    seed("TR-8", "빙그레 바나나맛 우유", "㈜빙그레", "원유, 설탕", "우유 함유")

                    translations.prewarmFeatured(listOf(LanguageCode.VI)) shouldBe 3

                    get("/api/processed-foods?lang=vi").path("items")[0].path("name").asText() shouldBe "[vi] 빙그레 바나나맛 우유"
                }
            }
        }
    }
}
