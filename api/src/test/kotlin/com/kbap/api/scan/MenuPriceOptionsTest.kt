package com.kbap.api.scan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuNameEstimation
import com.kbap.common.port.llm.MenuNameKind
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class MenuPriceOptionsTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var vision: FakeMenuBoardVisionExtractor
    @Autowired private lateinit var estimator: FakeMenuNameEstimator
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var redis: StringRedisTemplate

    private val mapper = jacksonObjectMapper()

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun token(memberId: Long): String {
            execute(
                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'opt-$memberId', 'ACTIVE', 1, '[]', 'ACTIVE', NOW(6), NOW(6))",
            )
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }
        fun scan(auth: String, path: String): JsonNode {
            val ticket = mapper.readTree(
                mockMvc.post("/api/scans/tickets") { header("Authorization", "Bearer $auth") }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("ticket").asText()
            return mapper.readTree(
                mockMvc.post("/api/scans") {
                    param("lang", "en"); param("currency", "USD")
                    header("Authorization", "Bearer $auth")
                    header("X-API-Version", "2.0")
                    header("X-Scan-Ticket", ticket)
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("imagePath" to path))
                }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")
        }
        fun pendingUntilDone(auth: String, scanId: String): JsonNode {
            repeat(50) {
                val body = mapper.readTree(
                    mockMvc.get("/api/scans/$scanId/pending") {
                        header("Authorization", "Bearer $auth")
                        header("X-API-Version", "2.0")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8),
                ).path("payload")
                if (body.path("done").asBoolean()) return body
                Thread.sleep(100)
            }
            error("pending 이 끝나지 않았다")
        }
        fun options(node: JsonNode) = node.path("priceOptions").map { it.path("label").asText() to it.path("priceKrw").asInt() }

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
            estimator.reset()
            redis.keys("menu-estimate:*").takeIf { it.isNotEmpty() }?.let { redis.delete(it) }
        }
        afterSpec {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM menu_name_estimate")
        }

        given("같은 메뉴의 수량·크기 옵션 합치기 — S-969a") {
            `when`("메뉴판이 같은 메뉴를 수량별 가격줄로 나눠 적었으면(경주빵 20개/10개)") {
                then("한 줄로 합치고 priceOptions 에 메뉴판 표기·가격을 순서대로, price 는 첫 옵션 값이며 pending 항목에도 같은 옵션이 실린다") {
                    val auth = token(96951)
                    vision.program(
                        "scans/opt-1.jpg",
                        listOf(
                            ExtractedMenu("경주빵 20개", "추정옵션경주빵", 22000, null, "20개"),
                            ExtractedMenu("경주빵 10개", "추정옵션경주빵", 11000, null, "10개"),
                            ExtractedMenu("찰보리빵 20개", "추정옵션찰보리빵", 20000, null, "20개"),
                            ExtractedMenu("찰보리빵 10개", "추정옵션찰보리빵", 10000, null, "10개"),
                        ),
                    )
                    estimator.answer = { names -> names.map { MenuNameEstimation(it, MenuNameKind.DISH, "Eng $it", null, emptyList(), 0, 0.9) } }

                    val payload = scan(auth, "scans/opt-1.jpg")

                    payload.path("results").size() shouldBe 2
                    payload.path("results")[0].let {
                        it.path("koreanName").asText() shouldBe "추정옵션경주빵"
                        it.path("price").asInt() shouldBe 22000
                        options(it) shouldBe listOf("20개" to 22000, "10개" to 11000)
                    }
                    options(payload.path("results")[1]) shouldBe listOf("20개" to 20000, "10개" to 10000)
                    pendingUntilDone(auth, payload.path("scanId").asText()).path("items")[0].let {
                        it.path("priceKrw").asInt() shouldBe 22000
                        options(it) shouldBe listOf("20개" to 22000, "10개" to 11000)
                    }
                }
            }

            `when`("매칭된 일반 메뉴에 크기 옵션이 있으면") {
                then("매칭 줄도 같은 규칙으로 한 줄 + priceOptions") {
                    foodRepository.save(
                        Food(
                            koreanName = "옵션완성찌개",
                            displayName = "옵션완성찌개",
                            imageRef = "images/food/opt.webp",
                            description = "완성",
                            longDescription = "완성 음식",
                            spiciness = 2,
                            ingredients = listOf(FoodIngredient("PORK", 100)),
                            contentStatus = FoodContentStatus.READY,
                        ),
                    )
                    vision.program("scans/opt-2.jpg", listOf(ExtractedMenu("찌개(소)", "옵션완성찌개", 9000, null, "소"), ExtractedMenu("찌개(대)", "옵션완성찌개", 15000, null, "대")))

                    scan(token(96952), "scans/opt-2.jpg").path("results").single().let {
                        it.path("matched").asBoolean() shouldBe true
                        options(it) shouldBe listOf("소" to 9000, "대" to 15000)
                    }
                }
            }

            `when`("이름이 다른 메뉴이거나 옵션이 하나뿐이면 — 반대쪽 결함") {
                then("합치지 않고 각자 한 줄, priceOptions 는 null 이다(같은 메뉴가 옵션 차이 없이 두 번 적힌 것은 기존처럼 두 줄)") {
                    vision.program(
                        "scans/opt-3.jpg",
                        listOf(
                            ExtractedMenu("김치찌개", "추정옵션김치찌개", 9000, null),
                            ExtractedMenu("김치찜", "추정옵션김치찜", 12000, null),
                            ExtractedMenu("김치찜", "추정옵션김치찜", 12000, null),
                        ),
                    )

                    val results = scan(token(96953), "scans/opt-3.jpg").path("results")

                    results.map { it.path("koreanName").asText() } shouldBe listOf("추정옵션김치찌개", "추정옵션김치찜", "추정옵션김치찜")
                    results.all { it.path("priceOptions").isNull } shouldBe true
                }
            }
        }
    }
}
