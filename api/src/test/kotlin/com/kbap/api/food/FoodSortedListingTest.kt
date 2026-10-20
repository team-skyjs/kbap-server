package com.kbap.api.food

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class FoodSortedListingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        fun seed(count: Int = 65) {
            TestTables.clearAll(dataSource)
            val jdbc = JdbcTemplate(dataSource)
            jdbc.update("INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) VALUES (89001, 'GOOGLE', 'sort-member', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))")
            (1..count).forEach { id ->
                val names = LanguageCode.entries.associate { it.code to "메뉴 ${count + 1 - id}" }
                jdbc.update("INSERT INTO food (id, korean_name, display_name, description, spiciness, name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) VALUES (?, ?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
                    id, "정렬$id", "메뉴 ${count + 1 - id}", mapper.writeValueAsString(names))
                jdbc.update("INSERT INTO bookmark (id, member_id, food_id, status, created_at, updated_at) VALUES (?, 89001, ?, 'ACTIVE', NOW(6), NOW(6))", count + 1 - id, id)
            }
        }

        fun request(path: String = "/api/foods", sort: String = "name_asc", cursor: String? = null, lang: String = "en", version: String = "3.1", risk: String? = null, expected: Int = 200, memberId: Long? = 89001): JsonNode {
            val response = mockMvc.get(path) {
                header("X-API-Version", version)
                memberId?.let { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}") }
                param("lang", lang)
                param("sort", sort)
                cursor?.let { param("cursor", it) }
                risk?.let { param("risk", it) }
            }.andReturn().response
            response.status shouldBe expected
            return mapper.readTree(response.contentAsString)
        }

        fun allIds(path: String, sort: String, lang: String): List<Long> {
            val ids = mutableListOf<Long>()
            var cursor: String? = null
            repeat(5) {
                val page = request(path, sort, cursor, lang).path("payload")
                ids += page.path("items").map { it.path("foodId").asLong() }
                if (!page.path("hasNext").asBoolean()) return ids
                page.path("nextCursor").isTextual shouldBe true
                cursor = page.path("nextCursor").asText()
            }
            error("페이지가 종료되지 않았습니다")
        }

        given("전체와 저장 음식의 표시 이름 정렬") {
            beforeEach { seed() }
            `when`("10개 언어로 세 페이지 이상 조회하면") {
                then("숫자 이름이 자연수순이고 역순은 전체 정순의 반전이다") {
                    LanguageCode.entries.forEach { lang ->
                        listOf("/api/foods", "/api/bookmarks").forEach { path ->
                            val asc = allIds(path, "name_asc", lang.code)
                            asc shouldBe (65L downTo 1L).toList()
                            allIds(path, "name_desc", lang.code) shouldBe asc.reversed()
                        }
                    }
                }
            }
            `when`("기존 기본 순서를 조회하면") {
                then("구 계약은 숫자이고 새 계약은 문자열 커서로 같은 순서를 유지한다") {
                    listOf("/api/foods", "/api/bookmarks").forEach { path ->
                        val old = request(path, "default", version = "1.1").path("payload")
                        val current = request(path, "default").path("payload")
                        current.path("items") shouldBe old.path("items")
                        old.path("nextCursor").isNumber shouldBe true
                        current.path("nextCursor").isTextual shouldBe true
                    }
                }
            }
            `when`("커서의 언어 또는 정렬 또는 위험도 필터를 바꾸면") {
                then("잘못된 경계에 이어 붙이지 않고 거절한다") {
                    val cursor = request().path("payload").path("nextCursor").asText()
                    request(cursor = cursor, lang = "ko", expected = 400).path("code").asText() shouldBe "FOOD-002"
                    request(cursor = cursor, sort = "name_desc", expected = 400).path("code").asText() shouldBe "FOOD-002"
                    request(cursor = cursor, risk = "SAFE", expected = 400).path("code").asText() shouldBe "FOOD-002"
                }
            }
            `when`("번역이나 저장 목록이 변경되면") {
                then("이전 페이지 커서는 재시작 오류가 된다") {
                    val cursor = request().path("payload").path("nextCursor").asText()
                    JdbcTemplate(dataSource).update("UPDATE food SET name_translations = JSON_SET(name_translations, '$.en', 'Changed') WHERE id = 1")
                    request(cursor = cursor, expected = 409).path("code").asText() shouldBe "FOOD-022"
                    val saved = request("/api/bookmarks").path("payload").path("nextCursor").asText()
                    JdbcTemplate(dataSource).update("UPDATE bookmark SET status = 'DELETED' WHERE food_id = 2")
                    request("/api/bookmarks", cursor = saved, expected = 409).path("code").asText() shouldBe "FOOD-022"
                }
            }
            `when`("기존 DB 콜레이션으로 숫자 이름을 비교하면") {
                then("자연수순과 달라 이름 정렬 비교기를 그대로 대체하지 못한다") {
                    JdbcTemplate(dataSource).queryForObject("SELECT _utf8mb4'메뉴 10' COLLATE utf8mb4_0900_ai_ci < _utf8mb4'메뉴 2' COLLATE utf8mb4_0900_ai_ci", Int::class.java) shouldBe 1
                }
            }
            `when`("필터에 맞지 않는 후보가 탐색 상한만큼 이어지면") {
                then("빈 페이지 뒤에서 다시 시작해 남은 적격 음식을 빠짐없이 반환한다") {
                    seed(520)
                    JdbcTemplate(dataSource).update("UPDATE food SET ingredients = NULL WHERE id > 20")
                    listOf("/api/foods", "/api/bookmarks").forEach { path ->
                        val first = request(path, risk = "SAFE").path("payload")
                        first.path("items").size() shouldBe 0
                        first.path("hasNext").asBoolean() shouldBe true
                        val last = request(path, cursor = first.path("nextCursor").asText(), risk = "SAFE").path("payload")
                        last.path("items").map { it.path("foodId").asLong() } shouldBe (20L downTo 1L).toList()
                        last.path("hasNext").asBoolean() shouldBe false
                        last.path("nextCursor").isNull shouldBe true
                    }
                }
            }
            `when`("동명과 누락 번역이 있으면") {
                then("실제로 반환하는 이름과 foodId로 정렬하고 저장 순서를 섞지 않는다") {
                    seed(3)
                    val jdbc = JdbcTemplate(dataSource)
                    jdbc.update("UPDATE food SET display_name = 'Apple', name_translations = '{\"en\":\" \"}' WHERE id = 1")
                    jdbc.update("UPDATE food SET name_translations = '{\"en\":\"apple\"}' WHERE id = 2")
                    jdbc.update("UPDATE food SET name_translations = '{\"en\":\"Banana\"}' WHERE id = 3")
                    listOf("/api/foods", "/api/bookmarks").forEach { path ->
                        allIds(path, "name_asc", "en") shouldBe listOf(1L, 2L, 3L)
                        allIds(path, "name_desc", "en") shouldBe listOf(3L, 2L, 1L)
                        request(path).path("payload").path("items").first().path("name").asText() shouldBe "Apple"
                    }
                }
            }
            `when`("프로필 또는 음식 공개 상태가 변경되면") {
                then("이미 받은 커서로는 계속 조회하지 않는다") {
                    val cursor = request().path("payload").path("nextCursor").asText()
                    JdbcTemplate(dataSource).update("UPDATE member SET avoidance_substance_codes = '[\"BEEF\"]' WHERE id = 89001")
                    request(cursor = cursor, expected = 409).path("code").asText() shouldBe "FOOD-022"
                    val next = request().path("payload").path("nextCursor").asText()
                    JdbcTemplate(dataSource).update("UPDATE food SET content_status = 'PENDING_REVIEW' WHERE id = 1")
                    request(cursor = next, expected = 409).path("code").asText() shouldBe "FOOD-022"
                }
            }
            `when`("잘못된 입력 또는 다른 회원·목록의 커서를 보내면") {
                then("입력 오류를 반환하고 저장 목록은 인증이 필요하다") {
                    request(sort = "bad", expected = 400).path("code").asText() shouldBe "COMMON-002"
                    request(lang = " ", expected = 400).path("code").asText() shouldBe "COMMON-002"
                    request(cursor = "123", expected = 400).path("code").asText() shouldBe "FOOD-002"
                    val cursor = request().path("payload").path("nextCursor").asText()
                    request("/api/bookmarks", cursor = cursor, expected = 400).path("code").asText() shouldBe "FOOD-002"
                    request(cursor = cursor, expected = 400, memberId = null).path("code").asText() shouldBe "FOOD-002"
                    request(memberId = null).path("success").asBoolean() shouldBe true
                    request("/api/bookmarks", memberId = null, expected = 401)
                    request(lang = "unsupported").path("payload") shouldBe request(lang = "en").path("payload")
                }
            }
            `when`("버전별 Swagger를 확인하면") {
                then("구 버전은 숫자 커서이고 새 버전만 정렬과 문자열 커서를 명시한다") {
                    fun doc(version: String): JsonNode = mapper.readTree(mockMvc.get("/v3/api-docs/$version").andReturn().response.contentAsString)
                    val old = doc("1.1")
                    val current = doc("3.1")
                    listOf("/api/foods", "/api/bookmarks").forEach { path ->
                        old.path("paths").path(path).path("get").path("parameters").any { it.path("name").asText() == "sort" } shouldBe false
                        current.path("paths").path(path).path("get").path("parameters").any { it.path("name").asText() == "sort" } shouldBe true
                        current.path("paths").path(path).path("get").path("description").asText().contains("FOOD-022") shouldBe true
                    }
                    old.path("components").path("schemas").path("PageFoodSummaryResponse").path("properties").path("nextCursor").path("type").asText() shouldBe "integer"
                    current.path("components").path("schemas").path("SearchPageFoodSummaryResponse").path("properties").path("nextCursor").path("type").asText() shouldBe "string"
                }
            }
        }
    }
}
