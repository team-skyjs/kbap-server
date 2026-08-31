package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class FoodScannedListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    override suspend fun afterSpec(spec: io.kotest.core.spec.Spec) {
        dataSource.connection.use { c ->
            c.prepareStatement("DELETE FROM scan_history WHERE member_id BETWEEN 5600 AND 5799").use { it.executeUpdate() }
        }
    }

    init {
        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, country_code, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "scanned-list-test-$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(
            id: Long,
            koreanName: String,
            englishName: String? = null,
            contentStatus: String = "READY",
        ): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, display_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, ?, '설명', 0, ?, '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.setString(3, koreanName)
                    ps.setString(4, englishName?.let { """{"en":"$it"}""" } ?: "{}")
                    ps.setString(5, contentStatus)
                    ps.executeUpdate()
                }
            }

        fun seedScan(memberId: Long, foodId: Long?, orderSeconds: Int): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO scan_history (member_id, price, food_id,
                                              status, created_at, updated_at)
                    VALUES (?, NULL, ?,
                            'ACTIVE', DATE_ADD(NOW(6), INTERVAL ? SECOND), NOW(6))
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    if (foodId == null) ps.setNull(2, java.sql.Types.BIGINT) else ps.setLong(2, foodId)
                    ps.setInt(3, orderSeconds)
                    ps.executeUpdate()
                }
            }

        fun scanned(
            token: String?,
            lang: String? = "en",
            cursor: String? = null,
            keyword: String? = "스캔",
            scope: String? = "scanned",
        ): ResultActionsDsl =
            mockMvc.get("/api/foods/search") {
                token?.let { header("Authorization", "Bearer $it") }
                scope?.let { param("scope", it) }
                lang?.let { param("lang", it) }
                cursor?.let { param("cursor", it) }
                keyword?.let { param("keyword", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun scannedList(token: String?, lang: String? = "en", cursor: String? = null): ResultActionsDsl =
            mockMvc.get("/api/foods/scanned") {
                token?.let { header("Authorization", "Bearer $it") }
                lang?.let { param("lang", it) }
                cursor?.let { param("cursor", it) }
            }

        given("스캔 음식 목록 — GET /api/foods/scanned") {
            `when`("음식 A→B→A 순으로 스캔한 회원이 목록을 조회하면") {
                then("중복 없이 마지막 스캔 시점 내림차순 [A, B] 로 내려간다") {
                    val token = accessToken(5611L)
                    seedFood(5751L, "목록김치찌개", "List Kimchi Stew")
                    seedFood(5752L, "목록비빔밥", "List Bibimbap")
                    seedScan(5611L, 5751L, 1)
                    seedScan(5611L, 5752L, 2)
                    seedScan(5611L, 5751L, 3)

                    val payload = payloadOf(scannedList(token))
                    payload.path("items").map { it.path("foodId").asLong() } shouldBe listOf(5751L, 5752L)
                    payload.path("hasNext").asBoolean().shouldBeFalse()
                    payload.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("스캔 음식이 21개인 회원이 목록을 조회하면") {
                then("첫 페이지 20건과 커서, 다음 페이지 1건으로 이어진다") {
                    val token = accessToken(5612L)
                    (1..21).forEach { i ->
                        seedFood(5760L + i, "목록페이징$i")
                        seedScan(5612L, 5760L + i, i)
                    }

                    val first = payloadOf(scannedList(token))
                    first.path("items").size() shouldBe 20
                    first.path("items").first().path("foodId").asLong() shouldBe 5781L
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    val nextCursor = first.path("nextCursor").asLong()
                    nextCursor shouldBe 5762L

                    val second = payloadOf(scannedList(token, cursor = nextCursor.toString()))
                    second.path("items").map { it.path("foodId").asLong() } shouldBe listOf(5761L)
                    second.path("hasNext").asBoolean().shouldBeFalse()
                    second.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("스캔 이력이 없는 회원이 목록을 조회하면") {
                then("빈 목록이 내려간다") {
                    payloadOf(scannedList(accessToken(5613L))).path("items").size() shouldBe 0
                }
            }
            `when`("토큰 없이 목록을 조회하면") {
                then("401 을 반환한다 — 회원 전용") {
                    scannedList(null).andExpect { status { isUnauthorized() } }
                }
            }
            `when`("비정상 형식·미보유 foodId 커서로 목록을 조회하면") {
                then("둘 다 400 FOOD-002 를 반환한다") {
                    val token = accessToken(5614L)
                    seedFood(5753L, "목록커서찌개")
                    seedScan(5614L, 5753L, 1)
                    scannedList(token, cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                    scannedList(token, cursor = "999999").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
            `when`("lang 없이 목록을 조회하면") {
                then("400 을 반환한다") {
                    scannedList(accessToken(5613L), lang = null).andExpect { status { isBadRequest() } }
                }
            }
        }

        given("스캔 음식 검색 — GET /api/foods/search?scope=scanned") {
            `when`("음식 A→B→A 순으로 스캔한 회원이 조회하면") {
                then("중복 없이 마지막 스캔 시점 내림차순 [A, B] 로 내려간다") {
                    val token = accessToken(5601L)
                    seedFood(5601L, "스캔김치찌개", "Scanned Kimchi Stew")
                    seedFood(5602L, "스캔비빔밥", "Scanned Bibimbap")
                    seedScan(5601L, 5601L, 1)
                    seedScan(5601L, 5602L, 2)
                    seedScan(5601L, 5601L, 3)

                    val payload = payloadOf(scanned(token))
                    payload.path("items").map { it.path("foodId").asLong() } shouldBe listOf(5601L, 5602L)
                    payload.path("items").first().path("name").asText() shouldBe "Scanned Kimchi Stew"
                    payload.path("hasNext").asBoolean().shouldBeFalse()
                    payload.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("스캔 이력이 없는 회원이 조회하면") {
                then("빈 목록이 내려간다") {
                    val token = accessToken(5602L)
                    payloadOf(scanned(token)).path("items").size() shouldBe 0
                }
            }
            `when`("READY 아닌 음식·음식 미매칭 스캔이 섞여 있으면") {
                then("둘 다 목록에서 제외된다") {
                    val token = accessToken(5603L)
                    seedFood(5603L, "스캔순두부", "Scanned Sundubu")
                    seedFood(5604L, "스캔미완성", contentStatus = "PENDING_REVIEW")
                    seedScan(5603L, 5603L, 1)
                    seedScan(5603L, 5604L, 2)
                    seedScan(5603L, null, 3)

                    val items = payloadOf(scanned(token)).path("items")
                    items.map { it.path("foodId").asLong() } shouldBe listOf(5603L)
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 AUTH-003 을 반환한다 — scope=scanned 는 회원 전용") {
                    scanned(null).andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("AUTH-003") }
                    }
                }
            }
            `when`("지원하지 않는 scope 값으로 조회하면") {
                then("400 COMMON-002 를 반환한다") {
                    scanned(accessToken(5610L), scope = "bookmarked").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
            `when`("keyword 를 누락하거나 빈 값으로 조회하면") {
                then("400 을 반환한다 — 검색어 없는 초기 화면은 스캔 내역 조회가 담당한다") {
                    scanned(accessToken(5610L), keyword = null).andExpect { status { isBadRequest() } }
                    scanned(accessToken(5610L), keyword = " ").andExpect { status { isBadRequest() } }
                }
            }
            `when`("스캔 음식이 21개인 회원이 조회하면") {
                then("페이징 없이 매칭 전체가 최신 스캔순으로 한 번에 내려간다") {
                    val token = accessToken(5604L)
                    (1..21).forEach { i ->
                        seedFood(5700L + i, "스캔페이징$i")
                        seedScan(5604L, 5700L + i, i)
                    }

                    val payload = payloadOf(scanned(token))
                    payload.path("items").size() shouldBe 21
                    payload.path("items").first().path("foodId").asLong() shouldBe 5721L
                    payload.path("items").last().path("foodId").asLong() shouldBe 5701L
                    payload.path("hasNext").asBoolean().shouldBeFalse()
                    payload.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("keyword 가 스캔 안 한 음식과도 일치하면") {
                then("본인 스캔 음식 범위 안에서만 매칭된다") {
                    val token = accessToken(5605L)
                    seedFood(5605L, "필터김치찌개", "Filter Kimchi Stew")
                    seedFood(5606L, "필터불고기", "Filter Bulgogi")
                    seedFood(5607L, "필터김치볶음밥", "Filter Kimchi Fried Rice")
                    seedScan(5605L, 5605L, 1)
                    seedScan(5605L, 5606L, 2)

                    val items = payloadOf(scanned(token, keyword = "김치")).path("items")
                    items.map { it.path("foodId").asLong() } shouldBe listOf(5605L)
                }
            }
            `when`("요청 언어 번역명으로 keyword 를 주면") {
                then("번역명 부분 일치로 매칭된다") {
                    val token = accessToken(5606L)
                    seedFood(5608L, "필터갈비탕", "Filter Galbitang")
                    seedScan(5606L, 5608L, 1)

                    val items = payloadOf(scanned(token, keyword = "galbi")).path("items")
                    items.map { it.path("foodId").asLong() } shouldBe listOf(5608L)
                }
            }
            `when`("lang 없이 조회하면") {
                then("400 을 반환한다") {
                    scanned(accessToken(5607L), lang = null).andExpect { status { isBadRequest() } }
                }
            }
            `when`("비정상 형식의 커서로 조회하면") {
                then("400 FOOD-002 를 반환한다 — 형식 검증은 scope 공통") {
                    scanned(accessToken(5608L), cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
            `when`("정상 형식의 커서를 주면") {
                then("scanned 는 페이징이 없어 커서가 무시되고 전체가 내려간다") {
                    val token = accessToken(5609L)
                    seedFood(5609L, "스캔커서찌개")
                    seedScan(5609L, 5609L, 1)
                    val payload = payloadOf(scanned(token, cursor = "999999"))
                    payload.path("items").map { it.path("foodId").asLong() } shouldBe listOf(5609L)
                    payload.path("hasNext").asBoolean().shouldBeFalse()
                }
            }
        }
    }
}
