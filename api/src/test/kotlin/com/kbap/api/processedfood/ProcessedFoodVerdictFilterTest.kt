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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodVerdictFilterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String?) = v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL"
        fun values(reportNo: String, name: String, allergens: String?, raw: String?) =
            "(${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, '과자', ${q("㈜필터$reportNo")}, ${q(raw)}, ${q(allergens)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))"
        fun seedAll(rows: List<String>) = execute(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) VALUES " +
                rows.joinToString(","),
        )
        fun milk(n: Int) = values("VF-M$n", "필터우유$n", "우유 함유", "원유 90%, 설탕")
        fun mayContainMilk(n: Int) = values("VF-C$n", "필터과자$n", "밀 함유. 이 제품은 우유를 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", "소맥분(밀), 설탕")
        fun safe(n: Int) = values("VF-S$n", "필터쌀과자$n", null, "쌀 90%, 설탕, 정제소금")
        fun member(id: Long, vararg avoiding: String): String {
            FoodTestSeed.seedMemberAvoiding(dataSource, id, *avoiding)
            return tokenIssuer.issueAccessToken(id, MemberRole.USER)
        }
        fun list(query: String, token: String? = null): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods?$query") {
                header("X-API-Version", "3.2")
                token?.let { header("Authorization", "Bearer $it") }
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun names(page: JsonNode) = page.path("items").map { it.path("name").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        given("가공식품 목록 판정 필터(KB-920)") {
            `when`("우유 회피 회원이 verdict=AVOID 로 목록을 보면") {
                then("AVOID 제품만 원래 순서대로 받는다") {
                    seedAll(listOf(safe(1), milk(1), mayContainMilk(1), safe(2), milk(2)))
                    val token = member(92001L, "MILK")

                    val page = list("verdict=AVOID", token)

                    names(page) shouldContainExactly listOf("필터우유1", "필터우유2")
                    page.path("items").map { it.path("verdict").asText() }.toSet() shouldBe setOf("AVOID")
                    page.path("hasNext").asBoolean() shouldBe false
                }
            }

            `when`("verdict 를 여러 개 주면") {
                then("그중 하나라도 맞는 제품을 받는다") {
                    seedAll(listOf(safe(1), milk(1), mayContainMilk(1)))
                    val token = member(92002L, "MILK")

                    names(list("verdict=AVOID&verdict=CAUTION", token)) shouldContainExactly listOf("필터우유1", "필터과자1")
                    names(list("verdict=SAFE", token)) shouldContainExactly listOf("필터쌀과자1")
                }
            }

            `when`("검색어와 함께 거르면") {
                then("검색 결과 안에서 판정으로 거른다") {
                    seedAll(listOf(milk(1), mayContainMilk(1), safe(1)))
                    val token = member(92003L, "MILK")

                    names(list("query=필터&verdict=CAUTION", token)) shouldContainExactly listOf("필터과자1")
                }
            }

            `when`("비회원이거나 회피 재료가 없는 회원이면") {
                then("verdict 를 무시하고 전체 목록을 준다") {
                    seedAll(listOf(safe(1), milk(1)))

                    names(list("verdict=AVOID")) shouldContainExactly listOf("필터쌀과자1", "필터우유1")
                    names(list("verdict=AVOID", member(92004L))) shouldContainExactly listOf("필터쌀과자1", "필터우유1")
                }
            }

            `when`("페이지를 채우기 전에 스캔 상한(200행)에 닿으면") {
                then("채운 만큼(빈 페이지 포함)과 다음 커서를 주고, 그 커서로 이어서 찾는다") {
                    seedAll((1..210).map { safe(it) } + milk(1))
                    val token = member(92005L, "MILK")

                    val first = list("verdict=AVOID&size=20", token)
                    first.path("items").size() shouldBe 0
                    first.path("hasNext").asBoolean() shouldBe true
                    first.path("nextCursor").asText() shouldBe "200"

                    val second = list("verdict=AVOID&size=20&cursor=200", token)
                    names(second) shouldContainExactly listOf("필터우유1")
                    second.path("hasNext").asBoolean() shouldBe false
                }
            }

            `when`("맞는 제품이 페이지 크기보다 많으면") {
                then("size 만큼 채우고 다음 커서는 마지막으로 살펴본 다음 위치다") {
                    seedAll(listOf(milk(1), safe(1), milk(2), milk(3), safe(2)))
                    val token = member(92006L, "MILK")

                    val first = list("verdict=AVOID&size=2", token)
                    names(first) shouldContainExactly listOf("필터우유1", "필터우유2")
                    first.path("nextCursor").asText() shouldBe "3"

                    val second = list("verdict=AVOID&size=2&cursor=3", token)
                    names(second) shouldContainExactly listOf("필터우유3")
                    second.path("hasNext").asBoolean() shouldBe false
                }
            }
        }
    }
}
