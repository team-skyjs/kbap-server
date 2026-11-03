package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.security.MessageDigest
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodTranslatedSearchTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var translationService: ProcessedFoodTranslationService

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String) = "'" + v.replace("'", "''") + "'"
        fun seed(reportNo: String, name: String, maker: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '과자', ${q(maker)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = ${q(reportNo)}").use { r -> r.next(); r.getLong(1) } }
            }
        }
        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun translation(type: String, productId: Long, language: String, text: String, hash: String) = execute(
            "INSERT INTO content_translation (target_type, target_id, language, source_hash, translated_text, source_language, status, created_at, updated_at) " +
                "VALUES ('$type', $productId, '$language', ${q(hash)}, ${q(text)}, 'ko', 'ACTIVE', NOW(6), NOW(6))",
        )
        fun pin(productId: Long, language: String, text: String) = translation("PF_NAME_PINNED", productId, language, text, "pinned")
        fun auto(productId: Long, name: String, maker: String, language: String, text: String) =
            translation("PF_NAME", productId, language, text, sha256("${ProcessedFoodTranslationService.CACHE_VERSION}\n${translationService.nameSourceOf(name, maker)}"))
        fun search(query: String): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods?query=$query") { header("X-API-Version", "3.2") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun reportNos(query: String): List<String> = search(query).path("payload").path("items").map { it.path("reportNo").asText() }

        beforeSpec { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("가공식품 검색 — 번역 이름 포함(S-945)") {
            `when`("한국어 원문엔 없는 영어 한 단어로 찾으면") {
                then("고정 번역 이름이 그 단어를 품은 상품이 나오고, 대소문자는 가리지 않는다") {
                    val milk = seed("TS-1", "바나나맛우유", "빙그레")
                    pin(milk, "en", "Binggrae Banana Milk")
                    reportNos("banana") shouldBe listOf("TS-1")
                    reportNos("BANANA milk") shouldBe listOf("TS-1")
                    reportNos("바나나") shouldBe listOf("TS-1")
                }
            }
            `when`("자동 번역(PF_NAME)이면") {
                then("현재 원문 해시와 맞는 번역만 검색에 쓰고, 원문이 바뀐 옛 번역은 쓰지 않는다") {
                    val current = seed("TS-2", "쿠키앤크림", "해태")
                    auto(current, "쿠키앤크림", "해태", "ja", "Cookies and Cream")
                    val stale = seed("TS-3", "허니버터칩", "해태")
                    translation("PF_NAME", stale, "en", "Cookies Butter Chip", "stale-hash")
                    reportNos("cookies") shouldBe listOf("TS-2")
                }
            }
            `when`("원문·고정 번역·자동 번역이 함께 맞으면") {
                then("원문 → 고정 번역 → 자동 번역 순으로 앞에 둔다") {
                    val byAuto = seed("TS-4", "꼬마초콜릿", "롯데")
                    auto(byAuto, "꼬마초콜릿", "롯데", "en", "Almondo Mini")
                    val byPin = seed("TS-5", "아몬드초콜릿바", "오리온")
                    pin(byPin, "en", "Almondo Chocolate Bar")
                    seed("TS-6", "Almondo초코", "크라운")
                    reportNos("almondo") shouldBe listOf("TS-6", "TS-5", "TS-4")
                }
            }
            `when`("라면 철자(ramyeon)로 찾고 한국어 이름엔 '라면'이 없으면 — Codex #535 2차") {
                then("영어 철자 그대로 번역 이름과도 맞춰 고정 영어 이름에 Ramyeon 이 든 상품이 나온다") {
                    val noodle = seed("TS-8", "짜장볶음면", "팔도")
                    pin(noodle, "en", "Jjajang Ramyeon")
                    seed("TS-9", "참깨라면", "오뚜기")
                    reportNos("ramyeon").toSet() shouldBe setOf("TS-8", "TS-9")
                }
            }
            `when`("고정 번역을 새로 넣으면") {
                then("재기동 없이 다음 검색부터 그 이름으로 찾힌다") {
                    val pie = seed("TS-7", "몽퐁케이크", "오리온")
                    reportNos("mongpong") shouldBe emptyList()
                    pin(pie, "en", "Orion Mongpong Cake")
                    reportNos("mongpong") shouldBe listOf("TS-7")
                }
            }
        }
    }
}
