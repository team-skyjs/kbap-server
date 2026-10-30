package com.kbap.api.processedfood

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodCvsListTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun q(v: String) = "'" + v.replace("'", "''") + "'"
        fun seed(reportNo: String, name: String, maker: String, foodType: String = "과자") = execute(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES (${q(reportNo)}, ${q(name)}, ${q(ProcessedFoodSnapshot.normalizeName(name))}, ${q(foodType)}, ${q(maker)}, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
        )
        fun list(query: String = ""): JsonNode = mapper.readTree(
            mockMvc.get("/api/processed-foods?$query") { header("X-API-Version", "3.2") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun names(page: JsonNode) = page.path("items").map { it.path("nameKo").asText() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
        }

        fun seedCatalog() {
            seed("1984044800212", "빙그레 바나나맛 우유", "빙그레", "가공유")
            seed("1998026202936", "빙그레 바나나맛 우유", "빙그레", "가공유")
            seed("1984044800213", "빙그레 딸기맛 우유", "빙그레", "가공유")
            seed("19870415003114", "초코파이", "오리온")
            seed("19870415003291", "꼬북칩 스윗시나몬맛", "오리온")
            seed("19870415003292", "꼬북칩 콘스프맛", "오리온")
            seed("19950383326120", "자가비 에그크랩", "해태제과")
            seed("1995038332662", "자가비 짭짤한 맛", "해태제과")
            seed("199204430031", "비락식혜", "팔도", "음료")
            seed("1992044300340", "밥알없는비락식혜", "팔도", "음료")
            seed("2016036417043", "치즈쿠키Ⅱ", "치즈제과")
            seed("2010046130335", "콘소메맛 팝콘", "팝콘제과")
            seed("20100461303274", "ONLY price 오리지널&카라멜 팝콘", "팝콘상회")
            seed("MART-ONLY-1", "마트전용 감자과자", "마트제과")
        }

        given("검색어 없는 가공식품 목록 — 편의점 상품만·시드 인기순(KB-938)") {
            `when`("편의점 판매 확인 상품이 있으면") {
                then("시드 라인 순위대로 라인마다 대표 1칸, 그다음 나머지 편의점 상품만 나오고 마트 전용 상품은 없다") {
                    seedCatalog()

                    val page = list("size=50")
                    val shown = names(page)

                    shown.take(7) shouldContainExactly listOf(
                        "빙그레 바나나맛 우유",
                        "빙그레 딸기맛 우유",
                        "초코파이",
                        "꼬북칩 스윗시나몬맛",
                        "자가비 짭짤한 맛",
                        "비락식혜",
                        "밥알없는비락식혜",
                    )
                    shown.drop(7) shouldContainExactlyInAnyOrder listOf("꼬북칩 콘스프맛", "자가비 에그크랩", "치즈쿠키Ⅱ", "콘소메맛 팝콘", "ONLY price 오리지널&카라멜 팝콘")
                    shown.count { it == "빙그레 바나나맛 우유" } shouldBe 1
                    shown.contains("마트전용 감자과자") shouldBe false
                    page.path("hasNext").asBoolean() shouldBe false
                }
            }

            `when`("같은 날 다시 부르거나 커서로 나눠 받으면") {
                then("순서가 같다 — 날짜 시드로 고정한 나머지 순서") {
                    seedCatalog()

                    val whole = names(list("size=50"))
                    names(list("size=50")) shouldContainExactly whole

                    val first = list("size=5")
                    val second = list("size=5&cursor=${first.path("nextCursor").asText()}")
                    val third = list("size=5&cursor=${second.path("nextCursor").asText()}")
                    (names(first) + names(second) + names(third)) shouldContainExactly whole
                }
            }

            `when`("유형 칩으로 거르면") {
                then("편의점 상품 중 그 유형만, 시드 순서를 지킨다") {
                    seedCatalog()

                    names(list("type=BEVERAGE&size=50")) shouldContainExactly listOf("비락식혜", "밥알없는비락식혜")
                }
            }

            `when`("검색어로 찾으면") {
                then("편의점 여부와 상관없이 전체 카탈로그에서 찾는다") {
                    seedCatalog()

                    names(list("query=감자과자")) shouldContainExactly listOf("마트전용 감자과자")
                }
            }

            `when`("편의점 판매 확인 상품이 DB 에 하나도 없으면") {
                then("기존 목록(전체 카탈로그) 그대로다") {
                    seed("MART-ONLY-1", "마트전용 감자과자", "마트제과")
                    seed("MART-ONLY-2", "마트전용 고구마과자", "마트제과")

                    names(list("size=50")) shouldContainExactlyInAnyOrder listOf("마트전용 감자과자", "마트전용 고구마과자")
                }
            }
        }
    }
}
