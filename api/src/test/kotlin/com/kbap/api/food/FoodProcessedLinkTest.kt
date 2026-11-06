package com.kbap.api.food

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.scan.ScanResult
import com.kbap.api.scan.ScanV2Response
import com.kbap.common.domain.CurrencyCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@IntegrationTest
class FoodProcessedLinkTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var popularFoodIdCache: PopularFoodIdCache

    private val mapper = jacksonObjectMapper()

    init {
        val adminId = 7301L
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun longOf(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun seedFood(koreanName: String): Long {
            execute(
                "INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                    "VALUES ('$koreanName', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
            return longOf("SELECT id FROM food WHERE korean_name = '$koreanName'")
        }
        fun seedProduct(reportNo: String, name: String, cutoutStatus: String?): Long {
            val cutout = cutoutStatus?.let { "'https://cdn.example.com/cutout/$reportNo.webp', '$it'" } ?: "NULL, NULL"
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, cutout_image_url, cutout_review_status, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '$name', '탄산음료', '코카콜라음료', '정제수', NULL, 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), $cutout, 'ACTIVE', NOW(6), NOW(6))",
            )
            return longOf("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'")
        }
        fun adminToken() = tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)
        fun body(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))
        fun link(foodId: Long, productId: Long, target: String = "dev") = mockMvc.put("/api/admin/foods/$foodId/processed-link") {
            header("X-API-Version", "1.0")
            header("Authorization", "Bearer ${adminToken()}")
            header("X-Target-Environment", target)
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("processedFoodId" to productId))
        }.andReturn().response
        fun unlink(foodId: Long) = body(
            mockMvc.delete("/api/admin/foods/$foodId/processed-link") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${adminToken()}")
                header("X-Target-Environment", "dev")
            }.andExpect { status { isOk() } }.andReturn().response,
        ).path("payload")
        fun foodDetail(foodId: Long) = body(
            mockMvc.get("/api/foods/$foodId?lang=ko") { header("X-API-Version", "1.0") }.andExpect { status { isOk() } }.andReturn().response,
        ).path("payload")
        fun adminDetail(foodId: Long) = body(
            mockMvc.get("/api/admin/foods/$foodId") {
                header("X-API-Version", "1.0")
                header("Authorization", "Bearer ${adminToken()}")
            }.andExpect { status { isOk() } }.andReturn().response,
        ).path("payload")

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("INSERT INTO admin_account (id, admin_id, admin_pwd, status, created_at, updated_at) VALUES ($adminId, 'link-admin', 'x', 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE status = 'ACTIVE'")
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("메뉴 음식 → 가공식품 연결 — S-968") {
            `when`("누끼 승인된 상품을 연결하면") {
                then("음식 상세·어드민 상세에 연결 상품이 실리고 연결한 운영진이 남는다") {
                    val cola = seedFood("연결콜라")
                    val coke = seedProduct("LINK-1", "코카-콜라", "APPROVED")

                    val response = link(cola, coke)

                    response.status shouldBe 200
                    body(response).path("payload").path("processedFood").path("id").asLong() shouldBe coke
                    foodDetail(cola).path("linkedProcessedFood").let {
                        it.path("id").asLong() shouldBe coke
                        it.path("name").asText() shouldBe "코카-콜라"
                        it.path("nameKo").asText() shouldBe "코카-콜라"
                        it.path("imageUrl").asText() shouldBe "https://cdn.example.com/cutout/LINK-1.webp"
                    }
                    adminDetail(cola).path("linkedProcessedFood").path("id").asLong() shouldBe coke
                    longOf("SELECT linked_by FROM food_processed_link WHERE food_id = $cola") shouldBe adminId
                }
            }

            `when`("연결이 없는 음식이면") {
                then("앱 음식 상세에 linkedProcessedFood 필드 자체가 없다") {
                    val plain = seedFood("연결없는국")
                    foodDetail(plain).has("linkedProcessedFood") shouldBe false
                }
            }

            `when`("누끼가 승인되지 않은 상품이면") {
                then("409 PFOOD-017 로 거절하고 연결하지 않는다") {
                    val cider = seedFood("연결사이다")
                    val pending = seedProduct("LINK-2", "칠성사이다", "PENDING")
                    val noCutout = seedProduct("LINK-3", "스프라이트", null)

                    listOf(pending, noCutout).forEach { productId ->
                        val response = link(cider, productId)
                        response.status shouldBe 409
                        body(response).path("code").asText() shouldBe "PFOOD-017"
                    }
                    longOf("SELECT COUNT(*) FROM food_processed_link") shouldBe 0
                }
            }

            `when`("대상 환경 헤더가 서버 환경과 다르면") {
                then("409 FOOD-026 으로 거절한다") {
                    val cola = seedFood("연결환경콜라")
                    val coke = seedProduct("LINK-4", "코카-콜라", "APPROVED")

                    val response = link(cola, coke, target = "prod")

                    response.status shouldBe 409
                    body(response).path("code").asText() shouldBe "FOOD-026"
                }
            }

            `when`("다른 상품으로 다시 연결하고 해제한 뒤 또 연결하면") {
                then("음식당 한 행을 바꿔 쓰고, 해제는 멱등이며, 다시 연결하면 그 행이 되살아난다") {
                    val cola = seedFood("연결재지정콜라")
                    val coke = seedProduct("LINK-5", "코카-콜라", "APPROVED")
                    val zero = seedProduct("LINK-6", "코카콜라 제로", "APPROVED")

                    link(cola, coke).status shouldBe 200
                    link(cola, zero).status shouldBe 200
                    foodDetail(cola).path("linkedProcessedFood").path("id").asLong() shouldBe zero

                    unlink(cola).path("unlinked").asBoolean() shouldBe true
                    unlink(cola).path("unlinked").asBoolean() shouldBe false
                    foodDetail(cola).has("linkedProcessedFood") shouldBe false

                    link(cola, coke).status shouldBe 200
                    foodDetail(cola).path("linkedProcessedFood").path("id").asLong() shouldBe coke
                    longOf("SELECT COUNT(*) FROM food_processed_link WHERE food_id = $cola") shouldBe 1
                }
            }
        }

        given("음식 카드(검색·홈)의 연결 상품 — S-973") {
            `when`("연결된 음식과 연결 없는 음식이 검색·홈 카드에 나오면") {
                then("연결된 카드에만 linkedProcessedFood 가 실리고 연결 없는 카드에는 필드가 없다") {
                    val cola = seedFood("카드연결콜라")
                    val soup = seedFood("카드연결국")
                    val coke = seedProduct("LINK-7", "코카-콜라", "APPROVED")
                    link(cola, coke).status shouldBe 200

                    val search = body(
                        mockMvc.get("/api/foods/search?lang=ko") { header("X-API-Version", "1.0"); param("keyword", "카드연결") }
                            .andExpect { status { isOk() } }.andReturn().response,
                    ).path("payload").path("items")
                    listOf(cola, soup).forEach { execute("INSERT INTO food_view_log (food_id, member_id, status, created_at, updated_at) VALUES ($it, NULL, 'ACTIVE', NOW(6), NOW(6))") }
                    popularFoodIdCache.invalidateAll()
                    val home = body(
                        mockMvc.get("/api/home?lang=ko") { header("X-API-Version", "1.0") }.andExpect { status { isOk() } }.andReturn().response,
                    ).path("payload").path("popularFoods")

                    listOf(search, home).forEach { cards ->
                        val byId = cards.associateBy { it.path("foodId").asLong() }
                        byId.getValue(cola).path("linkedProcessedFood").path("id").asLong() shouldBe coke
                        byId.getValue(cola).path("linkedProcessedFood").path("imageUrl").asText() shouldBe "https://cdn.example.com/cutout/LINK-7.webp"
                        byId.getValue(soup).has("linkedProcessedFood") shouldBe false
                    }
                }
            }
        }

        given("스캔 v2 응답의 연결 상품") {
            `when`("연결 맵에 있는 음식이 매칭 항목과 비매칭 항목에 각각 있으면") {
                then("매칭 항목에만 싣고 비매칭 항목에는 싣지 않는다") {
                    val linked = LinkedProcessedFoodResponse(2866, "Coca-Cola", "코카-콜라", "https://cdn.example.com/c.webp")
                    fun item(foodId: Long, matched: Boolean) = ScanResult.ItemRiskResult(
                        idx = 0, riskLevel = if (matched) "SAFE" else "UNKNOWN", matched = matched, foodId = foodId, name = "콜라", koreanName = "콜라",
                        price = 2000, imageRef = "default.webp", substituted = false, originalScannedName = null,
                    )
                    val response = ScanV2Response.from(
                        ScanResult(degraded = false, items = listOf(item(1, true), item(2, false))),
                        CurrencyCode.USD, null, exposeSubstitution = true,
                        linkedProcessedFoods = mapOf(1L to linked, 2L to linked),
                    )

                    response.results.map { it.linkedProcessedFood } shouldBe listOf(linked, null)
                }
            }
        }
    }
}
