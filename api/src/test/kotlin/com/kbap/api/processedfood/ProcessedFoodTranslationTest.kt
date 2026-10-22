package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.api.translation.FakeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodTranslationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var translations: ProcessedFoodTranslationService
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

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
        fun get(path: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get(path) {
                header("X-API-Version", "3.2")
                token?.let { header("Authorization", "Bearer $it") }
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun member(): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, 86801L)
            execute("UPDATE member SET scan_count = 0, scan_unlocked = 1 WHERE id = 86801")
            return tokenIssuer.issueAccessToken(86801L, MemberRole.USER)
        }
        fun resetCounter() = redis.delete("pf-translate:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))

        beforeEach {
            translations.awaitIdle()
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            translator.reset()
            translations.dailyBudgetKrw = 5000.0
            translations.syncDeadlineMillis = 3000L
            resetCounter()
        }
        afterSpec {
            translations.awaitIdle()
            translator.reset()
            translations.dailyBudgetKrw = 5000.0
            translations.syncDeadlineMillis = 3000L
            resetCounter()
        }

        given("가공식품 번역(KB-868)") {
            `when`("회원이 영어로 상세를 처음 보면") {
                then("이름은 바로 번역하고 제조사는 브랜드 사전(Binggrae)으로 주며, 원재료·알레르기 표시는 원문을 주고 뒤에서 번역해 다음부터 보인다") {
                    val token = member()
                    val id = seed("TR-1", "빙그레 바나나맛 우유", "㈜빙그레", "원유(국산) 85%, 정제수, 설탕", "우유 함유")

                    val p = get("/api/processed-foods/$id?lang=en", token)
                    p.path("name").asText() shouldBe "[en] 빙그레 바나나맛 우유"
                    p.path("nameKo").asText() shouldBe "빙그레 바나나맛 우유"
                    p.path("maker").asText() shouldBe "Binggrae"
                    p.path("ingredients").path("translated").asText() shouldBe "원유(국산) 85%, 정제수, 설탕"
                    p.path("allergenStatement").path("translated").asText() shouldBe "우유 함유"
                    translations.awaitIdle()
                    translator.transactionActiveDuringCalls.all { !it } shouldBe true

                    val again = get("/api/processed-foods/$id?lang=en", token)
                    again.path("ingredients").path("translated").asText() shouldBe "[en] 원유(국산) 85%, 정제수, 설탕"
                    again.path("ingredients").path("original").asText() shouldBe "원유(국산) 85%, 정제수, 설탕"
                    again.path("allergenStatement").path("translated").asText() shouldBe "[en] 우유 함유"
                    translator.calls.map { it.first }.toSet() shouldBe setOf("빙그레 바나나맛 우유", "원유(국산) 85%, 정제수, 설탕", "우유 함유")
                }
                then("저장된 번역은 다시 엔진을 부르지 않는다") {
                    val token = member()
                    val id = seed("TR-2", "새우깡", "㈜농심", "소맥분(밀), 새우", null)
                    get("/api/processed-foods/$id?lang=ja", token)
                    translations.awaitIdle()
                    val calls = translator.calls.size

                    get("/api/processed-foods/$id?lang=ja", token).path("name").asText() shouldBe "[ja] 새우깡"
                    translations.awaitIdle()
                    translator.calls.size shouldBe calls
                }
            }
            `when`("비회원이 상세를 보면 — 공부 #462") {
                then("엔진을 동기로 부르지 않고 원문을 준 뒤 뒤에서 번역해 다음부터 번역이 보인다") {
                    translator.reply = { text, target -> Thread.sleep(300); "[${target.code}] $text" }
                    val id = seed("TR-11", "비회원과자", "㈜동네식품", "밀가루", null)

                    get("/api/processed-foods/$id?lang=en").path("name").asText() shouldBe "비회원과자"
                    translations.awaitIdle()
                    get("/api/processed-foods/$id?lang=en").path("name").asText() shouldBe "[en] 비회원과자"
                }
            }
            `when`("동기 번역이 마감을 넘기면 — 공부 #462") {
                then("원문으로 응답하고 번역은 뒤에서 마저 저장된다") {
                    val token = member()
                    translations.syncDeadlineMillis = 200L
                    translator.reply = { text, target -> Thread.sleep(800); "[${target.code}] $text" }
                    val id = seed("TR-12", "느린과자", "㈜동네식품", null, null)

                    val started = System.nanoTime()
                    get("/api/processed-foods/$id?lang=en", token).path("name").asText() shouldBe "느린과자"
                    ((System.nanoTime() - started) < 700_000_000L) shouldBe true
                    translations.awaitIdle()
                    get("/api/processed-foods/$id?lang=en", token).path("name").asText() shouldBe "[en] 느린과자"
                }
            }
            `when`("한국어로 보면") {
                then("번역하지 않고 원문이 그대로다") {
                    val id = seed("TR-3", "꿀호떡", "㈜베이커리", "밀가루, 꿀", null)
                    val p = get("/api/processed-foods/$id?lang=ko", member())
                    p.path("name").asText() shouldBe "꿀호떡"
                    p.path("ingredients").path("translated").asText() shouldBe "밀가루, 꿀"
                    p.path("allergenStatement").isNull shouldBe true
                    translations.awaitIdle()
                    translator.calls.size shouldBe 0
                }
            }
            `when`("브랜드 사전에 없는 제조사면") {
                then("제조사도 LLM 으로 번역한다") {
                    val id = seed("TR-4", "동네과자", "㈜동네식품", "밀가루", null)
                    get("/api/processed-foods/$id?lang=en", member()).path("maker").asText() shouldBe "[en] 동네식품"
                }
            }
            `when`("엔진이 실패하면") {
                then("원문으로 폴백해 200 이다") {
                    translator.reply = { _, _ -> throw IllegalStateException("engine down") }
                    val id = seed("TR-5", "실패과자", "㈜동네식품", "밀가루", null)
                    val p = get("/api/processed-foods/$id?lang=en", member())
                    p.path("name").asText() shouldBe "실패과자"
                    p.path("ingredients").path("translated").asText() shouldBe "밀가루"
                }
            }
            `when`("엔진이 실패한 항목을 다시 요청하면 — 보안 검토") {
                then("같은 날에는 엔진을 다시 부르지 않고(실패 캐시) 실패한 호출도 일일 상한에 센다") {
                    val token = member()
                    translator.reply = { _, _ -> throw IllegalStateException("engine down") }
                    val id = seed("TR-9", "재시도과자", "㈜빙그레", null, null)
                    get("/api/processed-foods/$id?lang=en", token)
                    translations.awaitIdle()
                    val calls = translator.calls.size

                    get("/api/processed-foods/$id?lang=en", token).path("name").asText() shouldBe "재시도과자"
                    translations.awaitIdle()
                    translator.calls.size shouldBe calls

                    translations.dailyBudgetKrw = 0.4
                    translator.reply = { text, target -> "[${target.code}] $text" }
                    val other = seed("TR-10", "다음과자", "㈜빙그레", null, null)
                    get("/api/processed-foods/$other?lang=en", token).path("name").asText() shouldBe "다음과자"
                }
            }
            `when`("오늘 번역 시도가 일일 상한에 닿으면") {
                then("엔진을 부르지 않고 원문으로 폴백한다") {
                    translations.dailyBudgetKrw = 0.0
                    val id = seed("TR-6", "상한과자", "㈜동네식품", "밀가루", null)
                    get("/api/processed-foods/$id?lang=en", member()).path("name").asText() shouldBe "상한과자"
                    translations.awaitIdle()
                    translator.calls.size shouldBe 0
                }
            }
            `when`("이름 스캔에 lang 이 없으면 — Codex P2(#462 vOIe6)") {
                then("스캔과 같은 기본값(영어)으로 후보 번역을 예약해 다음 스캔부터 영어 이름이다") {
                    val token = member()
                    seed("TR-13", "스캔과자", "㈜동네식품", null, null)
                    fun scan() = mapper.readTree(
                        mockMvc.post("/api/processed-foods/scan/name") {
                            header("X-API-Version", "3.2")
                            header("Authorization", "Bearer $token")
                            contentType = MediaType.APPLICATION_JSON
                            content = mapper.writeValueAsString(mapOf("ocrText" to "스캔과자"))
                        }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("candidates")[0].path("name").asText()

                    scan() shouldBe "스캔과자"
                    translations.awaitIdle()
                    scan() shouldBe "[en] 스캔과자"
                }
            }
            `when`("대표 상품 미리 번역이 도는 중에 또 시작하면 — Codex P2(#462 vOIfE)") {
                then("두 번째는 건너뛰어 같은 원문을 두 번 번역하지 않는다") {
                    seed("TR-14", "빙그레 바나나맛 우유", "㈜빙그레", "원유, 설탕", "우유 함유")
                    translator.reply = { text, target -> Thread.sleep(200); "[${target.code}] $text" }

                    translations.prewarmFeaturedInBackground(listOf(LanguageCode.EN))
                    repeat(50) { if (translator.calls.isEmpty()) Thread.sleep(20) }
                    translations.prewarmFeatured(listOf(LanguageCode.EN)) shouldBe 0
                    translations.awaitIdle()

                    translator.calls.size shouldBe 3
                    translator.calls.map { it.first }.toSet().size shouldBe 3
                }
            }
            `when`("원문이 바뀌었는데 옛 원문 번역이 아직 돌고 있으면 — Codex P2(#462 vOIfL)") {
                then("옛 번역을 기다려 돌려주지 않고 새 원문을 번역한다") {
                    val token = member()
                    translations.syncDeadlineMillis = 100L
                    translator.reply = { text, target -> if (text == "옛이름과자") Thread.sleep(800); "[${target.code}] $text" }
                    val id = seed("TR-15", "옛이름과자", "㈜동네식품", null, null)

                    get("/api/processed-foods/$id?lang=en", token).path("name").asText() shouldBe "옛이름과자"
                    execute("UPDATE processed_food_product SET product_name = '새이름과자' WHERE id = $id")
                    translations.syncDeadlineMillis = 3000L

                    get("/api/processed-foods/$id?lang=en", token).path("name").asText() shouldBe "[en] 새이름과자"
                }
            }
            `when`("번역이 저장된 목록을 영어로 보면 — Codex P2(#462 vOvEw)") {
                then("번역 캐시를 항목마다가 아니라 한 번에 읽어 항목 수와 무관하게 쿼리 수가 같다") {
                    (1..3).forEach { seed("TR-B$it", "묶음과자$it", "㈜동네식품$it", null, null) }
                    get("/api/processed-foods?size=3&lang=en")
                    translations.awaitIdle()

                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    val originallyEnabled = statistics.isStatisticsEnabled
                    statistics.isStatisticsEnabled = true
                    try {
                        val counts = listOf(1, 3).map { size ->
                            statistics.clear()
                            get("/api/processed-foods?size=$size&lang=en").path("items").map { it.path("name").asText() }.all { it.startsWith("[en] ") } shouldBe true
                            statistics.prepareStatementCount
                        }
                        counts.last() shouldBe counts.first()
                    } finally {
                        statistics.isStatisticsEnabled = originallyEnabled
                    }
                }
            }
            `when`("목록에서 저장된 제조사 번역을 다시 보면 — 공부 #462(제조사 캐시 해시)") {
                then("저장할 때와 읽을 때 같은 표시 제조사로 해시해 두 번째부터 제조사 엔진 호출이 0 이다") {
                    val id = seed("TR-M1", "제조사과자", "㈜동네식품", null, null)
                    get("/api/processed-foods?query=제조사과자&lang=en")
                    translations.awaitIdle()
                    translator.calls.count { it.first == "동네식품" } shouldBe 1
                    translator.calls.clear()

                    get("/api/processed-foods?query=제조사과자&lang=en").path("items")[0].path("maker").asText() shouldBe "[en] 동네식품"
                    get("/api/processed-foods/$id?lang=en").path("maker").asText() shouldBe "[en] 동네식품"
                    translations.awaitIdle()
                    translator.calls.count { it.first == "동네식품" } shouldBe 0
                }
            }
            `when`("대표 상품이 카탈로그 별칭 이름 행으로만 있으면 — Codex P2(#462 vOvFC)") {
                then("목록과 같은 이름+별칭 해석으로 미리 번역 대상에 든다") {
                    seed("TR-A1", "바나나맛 우유", "㈜빙그레", "원유, 설탕", "우유 함유")

                    translations.prewarmFeatured(listOf(LanguageCode.EN)) shouldBe 3
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
