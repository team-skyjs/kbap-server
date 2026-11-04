package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.processedfood.ProcessedFoodTranslationService
import com.kbap.api.translation.FakeTextTranslator
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
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class AdminProcessedFoodRecipeTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var translator: FakeTextTranslator
    @Autowired private lateinit var translations: ProcessedFoodTranslationService

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun seed(reportNo: String, name: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '유탕면', '삼양식품', '소맥분', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'").use { r -> r.next(); r.getLong(1) } } }
        }
        fun read(result: ResultActionsDsl, expected: Int): JsonNode =
            mapper.readTree(result.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8))
        fun putRecipe(id: Long, recipe: Any?, expected: Int = 200) = read(
            mockMvc.put("/api/admin/processed-foods/$id/recipe") {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("recipe" to recipe))
            },
            expected,
        )
        fun detail(id: Long, lang: String? = null): JsonNode = read(
            mockMvc.get("/api/processed-foods/$id") { header("X-API-Version", "3.2"); lang?.let { param("lang", it) } },
            200,
        ).path("payload")

        val buldak = mapOf(
            "source" to "LABEL",
            "methods" to listOf(
                mapOf(
                    "type" to "MICROWAVE",
                    "water" to mapOf("ml" to null, "line" to "INNER"),
                    "power" to listOf(mapOf("watt" to 1000, "seconds" to 150), mapOf("watt" to 700, "seconds" to 180)),
                    "steps" to listOf(mapOf("ko" to "뚜껑을 절취선까지 뜯는다"), mapOf("ko" to "안쪽 선까지 물을 붓는다")),
                    "cautions" to listOf("물을 버리지 마세요"),
                ),
                mapOf(
                    "type" to "BOILING_WATER",
                    "water" to mapOf("ml" to null, "line" to "OUTER"),
                    "waitSeconds" to 240,
                    "drainKeepMl" to 15,
                    "steps" to listOf(mapOf("ko" to "끓는 물을 바깥 선까지 붓는다")),
                ),
            ),
        )
        fun withMethod(change: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            val method = ((buldak["methods"] as List<Map<String, Any?>>)[0]).toMutableMap()
            change(method)
            return mapOf("source" to "LABEL", "methods" to listOf(method))
        }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            translations.awaitIdle()
            translator.reset()
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 조리법 — S-961") {
            `when`("어드민이 조리법을 넣으면") {
                then("상세 recipe 에 방법별 숫자·단계·주의가 나오고, 없는 상품은 recipe 필드가 없다") {
                    val id = seed("RC-1", "로제불닭볶음면 큰컵")
                    val other = seed("RC-9", "조리법없는과자")

                    putRecipe(id, buldak)

                    val recipe = detail(id).path("recipe")
                    recipe.path("source").asText() shouldBe "LABEL"
                    recipe.path("methods")[0].let {
                        it.path("type").asText() shouldBe "MICROWAVE"
                        it.path("water").path("line").asText() shouldBe "INNER"
                        it.path("water").path("ml").isNull shouldBe true
                        it.path("power").map { p -> p.path("watt").asInt() to p.path("seconds").asInt() } shouldBe listOf(1000 to 150, 700 to 180)
                        it.path("steps").map { s -> s.asText() } shouldBe listOf("뚜껑을 절취선까지 뜯는다", "안쪽 선까지 물을 붓는다")
                        it.path("cautions").map { c -> c.asText() } shouldBe listOf("물을 버리지 마세요")
                    }
                    recipe.path("methods")[1].let {
                        it.path("waitSeconds").asInt() shouldBe 240
                        it.path("drainKeepMl").asInt() shouldBe 15
                    }
                    detail(other).has("recipe") shouldBe false
                }
            }

            `when`("recipe 를 null 로 넣으면") {
                then("지워져 상세에서 필드가 사라진다(전체 교체)") {
                    val id = seed("RC-1", "로제불닭볶음면 큰컵")
                    putRecipe(id, buldak)

                    putRecipe(id, null)

                    detail(id).has("recipe") shouldBe false
                }
            }

            `when`("범위를 벗어난 값이면") {
                then("400 COMMON-002 이고 저장하지 않는다 — 단계 1~12, 와트 100~2000, 초 0~3600, ml 0~2000") {
                    val id = seed("RC-1", "로제불닭볶음면 큰컵")
                    putRecipe(id, withMethod { it["steps"] = (1..13).map { n -> mapOf("ko" to "단계$n") } }, 400).path("code").asText() shouldBe "COMMON-002"
                    putRecipe(id, withMethod { it["steps"] = emptyList<Any>() }, 400).path("code").asText() shouldBe "COMMON-002"
                    putRecipe(id, withMethod { it["power"] = listOf(mapOf("watt" to 50, "seconds" to 100)) }, 400).path("code").asText() shouldBe "COMMON-002"
                    putRecipe(id, withMethod { it["power"] = listOf(mapOf("watt" to 700, "seconds" to 4000)) }, 400).path("code").asText() shouldBe "COMMON-002"
                    putRecipe(id, withMethod { it["water"] = mapOf("ml" to 2500, "line" to null) }, 400).path("code").asText() shouldBe "COMMON-002"
                    putRecipe(Long.MAX_VALUE, buldak, 404).path("code").asText() shouldBe "PFOOD-001"

                    detail(id).has("recipe") shouldBe false
                }
            }

            `when`("묶음의 다른 상품에만 조리법이 있으면") {
                then("대표 상품(이름이 가장 짧은 것)의 조리법을 쓴다") {
                    val bag = seed("RC-1", "까르보불닭볶음면")
                    val cup = seed("RC-2", "까르보불닭볶음면 큰컵")
                    putRecipe(bag, buldak)

                    detail(cup).path("recipe").path("methods").size() shouldBe 2
                }
            }

            `when`("영어로 상세를 보면") {
                then("단계·주의가 번역되고(언어당 묶음 번역), 줄 수가 어긋난 번역은 한국어 원문으로 둔다") {
                    val id = seed("RC-1", "로제불닭볶음면 큰컵")
                    putRecipe(id, buldak)
                    translator.reply = { text, target -> text.lines().joinToString("\n") { "[${target.code}] $it" } }

                    detail(id, "en")
                    translations.awaitIdle()
                    val methods = detail(id, "en").path("recipe").path("methods")

                    methods[0].path("steps").map { it.asText() } shouldBe listOf("[en] 뚜껑을 절취선까지 뜯는다", "[en] 안쪽 선까지 물을 붓는다")
                    methods[1].path("steps").map { it.asText() } shouldBe listOf("[en] 끓는 물을 바깥 선까지 붓는다")
                    methods[0].path("cautions").map { it.asText() } shouldBe listOf("[en] 물을 버리지 마세요")

                    translator.reply = { text, _ -> text.lines().joinToString(" ") }
                    detail(id, "ja")
                    translations.awaitIdle()
                    detail(id, "ja").path("recipe").path("methods")[0].path("steps").map { it.asText() } shouldBe listOf("뚜껑을 절취선까지 뜯는다", "안쪽 선까지 물을 붓는다")
                }
            }
        }
    }
}
