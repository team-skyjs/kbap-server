package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.food.FoodTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun seedProduct(
            reportNo: String,
            name: String,
            manufacturer: String? = null,
            allergens: String? = null,
            raw: String? = null,
            imageUrl: String? = null,
            seenDaysAgo: Int = 0,
        ): Long {
            fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
            val normalized = name.replace(Regex("[\\s()·]|오리지널"), "")
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, " +
                    "image_url, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                    "(${q(reportNo)}, ${q(name)}, ${q(normalized)}, '과자', ${q(manufacturer)}, ${q(raw)}, ${q(allergens)}, ${q(imageUrl)}, 'HACCP', " +
                    "REPEAT('a', 64), NOW(6) - INTERVAL $seenDaysAgo DAY, NOW(6) - INTERVAL $seenDaysAgo DAY, 'ACTIVE', NOW(6), NOW(6))",
            )
            return dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM processed_food_product WHERE report_no = ?").use { ps ->
                    ps.setString(1, reportNo)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }
        }

        fun get(path: String, version: String = "3.2", token: String? = null, expected: Int = 200): JsonNode =
            mapper.readTree(
                mockMvc.get(path) {
                    header("X-API-Version", version)
                    token?.let { header("Authorization", "Bearer $it") }
                }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        beforeSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }
        afterSpec { execute("DELETE FROM processed_food_product") }

        given("가공식품 검색 — GET /api/processed-foods?query=") {
            seedProduct("PF-S-1", "새우깡", "농심_구미공장")
            seedProduct("PF-S-2", "새우깡블랙", "농심_구미공장")
            seedProduct("PF-S-3", "매운새우깡", "농심_구미공장")
            seedProduct("PF-S-4", "신라면", "알수없음")
            seedProduct("PF-S-5", "신라면블랙", "농심_부산공장")
            seedProduct("PF-S-6", "포카칩오리지널", "오리온_청주시")
            seedProduct("PF-S-7", "단종된새우깡", "농심", seenDaysAgo = 10)
            seedProduct("PF-S-8", "100%_새우깡", "농심")

            fun names(json: JsonNode) = json.path("payload").path("items").map { it.path("productName").asText() }

            `when`("제품명을 그대로 치면") {
                then("완전 일치 → 접두 → 포함 순이고, 생산종료(최근 미러보다 3일 넘게 안 보인 제품)는 빠진다") {
                    names(get("/api/processed-foods?query=새우깡")) shouldBe listOf("새우깡", "새우깡블랙", "매운새우깡", "100%_새우깡")
                }
            }
            `when`("제조사와 제품명을 함께 치면") {
                then("제조사 토큰은 제조사에, 나머지는 제품명에 맞춘다 — 제조사가 알수없음이어도 이름이 같으면 맨 앞") {
                    names(get("/api/processed-foods?query=농심 신라면")) shouldBe listOf("신라면", "신라면블랙")
                }
            }
            `when`("공백·'오리지널'을 섞어 치면") {
                then("미러와 같은 정규화로 맞춘다") {
                    names(get("/api/processed-foods?query=포카칩 오리지널")) shouldBe listOf("포카칩오리지널")
                }
            }
            `when`("% 나 _ 를 치면") {
                then("와일드카드가 아니라 글자로 찾는다") {
                    names(get("/api/processed-foods?query=100%_")) shouldBe listOf("100%_새우깡")
                }
            }
            `when`("size 보다 결과가 많으면") {
                then("nextCursor 로 이어 받으면 겹치지 않고 전부 이어진다") {
                    val first = get("/api/processed-foods?query=새우깡&size=2")
                    names(first) shouldBe listOf("새우깡", "새우깡블랙")
                    first.path("payload").path("hasNext").asBoolean() shouldBe true
                    val cursor = first.path("payload").path("nextCursor").asText()
                    val second = get("/api/processed-foods?query=새우깡&size=2&cursor=$cursor")
                    names(second) shouldBe listOf("매운새우깡", "100%_새우깡")
                    second.path("payload").path("hasNext").asBoolean() shouldBe false
                }
            }
            `when`("검색어가 길거나 토큰이 많으면 — 토큰마다 쿼리가 돌아 증폭되지 않게") {
            then("100자 초과는 400, 토큰은 앞 5개만 쓴다") {
                    get("/api/processed-foods?query=" + "가".repeat(101), expected = 400)
                    names(get("/api/processed-foods?query=새우깡 가 나 다 라 포카칩")) shouldBe listOf("새우깡", "새우깡블랙", "매운새우깡", "100%_새우깡")
                }
            }
            `when`("구버전 헤더로 부르면") {
                then("가공식품 경로는 3.2 미만에서 열리지 않는다") {
                    get("/api/processed-foods?query=새우깡", version = "3.1", expected = 404)
                }
            }
        }

        given("가공식품 상세 — GET /api/processed-foods/{id}") {
            val declared = seedProduct(
                "PF-D-1", "새우버터칩", "농심_구미공장",
                allergens = "새우, 우유 함유", raw = "소맥분(밀:미국산), 가공버터{유지방(우유)}, 버터향", imageUrl = "http://img/1.jpg",
            )
            val ingredientsOnly = seedProduct("PF-D-2", "땅콩강정", "알수없음", allergens = "알수없음", raw = "땅콩, 물엿")
            val none = seedProduct("PF-D-3", "물", "알수없음", allergens = "알수없음", raw = null)
            val stale = seedProduct("PF-D-4", "단종칩", "농심", seenDaysAgo = 10)

            `when`("비회원이 알레르기 표시가 있는 제품을 보면") {
                then("원문·표시 출처 구분·검출·근거·출처 고지 키·이미지가 오고 회원 대조는 null 이다") {
                    val p = get("/api/processed-foods/$declared").path("payload")
                    p.path("productName").asText() shouldBe "새우버터칩"
                    p.path("maker").asText() shouldBe "농심"
                    p.path("rawMaterials").asText() shouldBe "소맥분(밀:미국산), 가공버터{유지방(우유)}, 버터향"
                    p.path("allergens").asText() shouldBe "새우, 우유 함유"
                    p.path("labelSource").asText() shouldBe "DECLARED"
                    p.path("detections").map { it.path("code").asText() + ":" + it.path("source").asText() } shouldBe
                        listOf("SHRIMP:ALLERGEN_DECLARATION", "MILK:ALLERGEN_DECLARATION", "WHEAT:RAW_MATERIALS", "BUTTER:RAW_MATERIALS")
                    p.path("detections").first { it.path("code").asText() == "WHEAT" }.path("evidence").asText() shouldBe "소맥분"
                    p.path("source").path("provider").asText() shouldBe "HACCP"
                    p.path("source").path("attributionKey").asText() shouldBe "processedFood.source.haccp"
                    p.path("image").path("url").asText() shouldBe "http://img/1.jpg"
                    p.path("image").path("attributionKey").asText() shouldBe "processedFood.image.haccp"
                    p.path("avoidance").isNull shouldBe true
                }
            }
            `when`("우유·땅콩을 피하는 회원이 보면") {
                then("우유는 DETECTED(근거와 함께), 땅콩은 UNCONFIRMED — '없음/안전' 값은 없다") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 88101L, "MILK", "PEANUT")
                    val token = tokenIssuer.issueAccessToken(88101L, MemberRole.USER)
                    val raw = get("/api/processed-foods/$declared", token = token)
                    val avoidance = raw.path("payload").path("avoidance").associate { it.path("code").asText() to it }
                    avoidance.getValue("MILK").path("match").asText() shouldBe "DETECTED"
                    avoidance.getValue("MILK").path("matchedCodes").map { it.asText() }.toSet() shouldBe setOf("MILK", "BUTTER")
                    avoidance.getValue("PEANUT").path("match").asText() shouldBe "UNCONFIRMED"
                    raw.toString() shouldNotContain "SAFE"
                }
            }
            `when`("알레르기 표시가 '알수없음'이면") {
                then("원재료가 있으면 INGREDIENTS_ONLY, 둘 다 없으면 NONE 이다") {
                    get("/api/processed-foods/$ingredientsOnly").path("payload").path("labelSource").asText() shouldBe "INGREDIENTS_ONLY"
                    get("/api/processed-foods/$none").path("payload").let {
                        it.path("labelSource").asText() shouldBe "NONE"
                        it.path("image").isNull shouldBe true
                    }
                }
            }
            `when`("없는 id 나 생산종료 제품이면") {
                then("404 PFOOD-001 · 410 PFOOD-002") {
                    get("/api/processed-foods/999999999", expected = 404).path("code").asText() shouldBe "PFOOD-001"
                    get("/api/processed-foods/$stale", expected = 410).path("code").asText() shouldBe "PFOOD-002"
                }
            }
        }
    }
}
