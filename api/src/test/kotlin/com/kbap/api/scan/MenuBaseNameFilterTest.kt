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
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class MenuBaseNameFilterTest : BehaviorSpec() {
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
        fun count(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun token(memberId: Long): String {
            execute(
                "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, avoidance_substance_codes, status, created_at, updated_at) " +
                    "VALUES ($memberId, 'GOOGLE', 'base-$memberId', 'ACTIVE', 1, '[]', 'ACTIVE', NOW(6), NOW(6))",
            )
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }
        fun ready(name: String) = foodRepository.save(
            Food(
                koreanName = name,
                displayName = name,
                imageRef = "images/food/$name.webp",
                description = "설명",
                longDescription = "$name 긴 설명",
                spiciness = 1,
                ingredients = listOf(FoodIngredient("RICE", 100)),
                contentStatus = FoodContentStatus.READY,
            ),
        )
        fun scan(auth: String, path: String): JsonNode {
            val ticket = mapper.readTree(
                mockMvc.post("/api/scans/tickets") { header("Authorization", "Bearer $auth") }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("ticket").asText()
            return mapper.readTree(
                mockMvc.post("/api/scans") {
                    param("lang", "ko"); param("currency", "USD")
                    header("Authorization", "Bearer $auth")
                    header("X-API-Version", "3.0")
                    header("X-Scan-Ticket", ticket)
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(mapOf("imagePath" to path))
                }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")
        }
        fun foodCount(name: String) = count("SELECT COUNT(*) FROM food WHERE korean_name = '$name'")

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

        given("기본 음식명 정확 일치 — S-970") {
            `when`("원문은 DB 에 없고 baseName 이 READY 음식과 같으면") {
                then("그 음식으로 대체(substituted·originalScannedName)하고, 원문으로 미완성 음식·AI 추정을 만들지 않는다") {
                    val friedRice = ready("기본볶음밥")
                    vision.program(
                        "scans/base-1.jpg",
                        listOf(
                            ExtractedMenu("불판 위에 볶음밥", "불판위에기본볶음밥", 9000, null, baseName = "기본볶음밥"),
                            ExtractedMenu("설화 소갈비 세트", "설화기본소갈비세트", 30000, null, baseName = "기본소갈비"),
                        ),
                    )
                    val galbi = ready("기본소갈비")

                    val results = scan(token(97401), "scans/base-1.jpg").path("results")

                    results[0].let {
                        it.path("matched").asBoolean() shouldBe true
                        it.path("foodId").asLong() shouldBe friedRice.id
                        it.path("substituted").asBoolean() shouldBe true
                        it.path("originalScannedName").asText() shouldBe "불판위에기본볶음밥"
                        it.path("pendingId").isNull shouldBe true
                    }
                    results[1].path("foodId").asLong() shouldBe galbi.id
                    foodCount("불판위에기본볶음밥") shouldBe 0
                    foodCount("설화기본소갈비세트") shouldBe 0
                }
            }

            `when`("원문이 READY 로 있으면") {
                then("baseName 보다 원문이 우선이다(차돌된장찌개 → 된장찌개로 바꾸지 않음)") {
                    val chadol = ready("기본차돌된장찌개")
                    ready("기본된장찌개")
                    vision.program("scans/base-2.jpg", listOf(ExtractedMenu("차돌된장찌개", "기본차돌된장찌개", 11000, null, baseName = "기본된장찌개")))

                    scan(token(97402), "scans/base-2.jpg").path("results").single().let {
                        it.path("foodId").asLong() shouldBe chadol.id
                        it.path("substituted").asBoolean() shouldBe false
                    }
                }
            }

            `when`("baseName 의 음식이 READY 가 아니면") {
                then("대체하지 않고 기존처럼 미완성 음식·AI 추정으로 간다") {
                    execute("INSERT INTO food (korean_name, display_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES ('기본미완성찌개', '기본미완성찌개', '', 0, '{}', '{}', '[]', 'FAILED', 'ACTIVE', NOW(6), NOW(6))")
                    vision.program("scans/base-3.jpg", listOf(ExtractedMenu("할매손맛 미완성찌개", "할매기본미완성찌개", 9000, null, baseName = "기본미완성찌개")))

                    scan(token(97403), "scans/base-3.jpg").path("results").single().let {
                        it.path("substituted").asBoolean() shouldBe false
                        it.path("pendingId").isNull shouldBe false
                    }
                    foodCount("할매기본미완성찌개") shouldBe 1
                }
            }
        }

        given("메뉴 아닌 이름 필터 — S-970") {
            `when`("주문 옵션·단일 재료어면(추가·사리·곱빼기·변경·소스·토핑·공기밥·세트 단독·고기·계란)") {
                then("notMenu=true 이고 대체·AI 추정·미완성 음식 생성을 하지 않는다") {
                    val names = listOf("치즈추가", "라면사리", "곱빼기", "맵기변경", "칠리소스", "치즈토핑", "공기밥", "세트", "고기", "계란")
                    vision.program("scans/base-4.jpg", names.map { ExtractedMenu(it, it, 1000, null) })

                    val results = scan(token(97404), "scans/base-4.jpg").path("results")

                    results.map { it.path("notMenu").asBoolean() } shouldBe names.map { true }
                    results.all { it.path("pendingId").isNull && it.path("foodId").isNull && it.path("riskLevel").asText() == "UNKNOWN" } shouldBe true
                    names.forEach { foodCount(it) shouldBe 0 }
                }
            }

            `when`("실제 메뉴·DB 에 없는 술이면 — 오탐 방지(술은 걸러지면 알코올 회피 경고가 사라진다)") {
                then("걸리지 않고 기존처럼 미완성 음식·AI 추정으로 간다") {
                    val names = listOf("맥주수육", "고기국수", "와인삼겹살", "감자탕", "계란말이", "치즈돈까스", "수제비", "탕수육세트", "장인생막걸리", "솔뫼양조장탁주")
                    vision.program("scans/base-5.jpg", names.map { ExtractedMenu(it, it, 9000, null) })

                    val results = scan(token(97405), "scans/base-5.jpg").path("results")

                    results.map { it.path("notMenu").asBoolean() } shouldBe names.map { false }
                    results.all { !it.path("pendingId").isNull } shouldBe true
                }
            }
        }
    }
}
