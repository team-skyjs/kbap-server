package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class GlobalReviewListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedMember(memberId: Long, countryCode: String? = "KR"): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, country_code, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE country_code = VALUES(country_code)
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "review-feed-test-$memberId")
                    ps.setString(3, "피더$memberId")
                    ps.setString(4, countryCode)
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long, countryCode: String? = "KR"): String {
            seedMember(memberId, countryCode)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, imageRef: String? = null): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, image_ref, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, ?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.setString(3, imageRef)
                    ps.executeUpdate()
                }
            }

        fun softDeleteFood(id: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE food SET status = 'DELETED' WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeUpdate()
                }
            }

        fun createReview(token: String, foodId: Long, rating: Int = 4): Long {
            val response = mockMvc.post("/api/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to rating))
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("reviewId").asLong()
        }

        fun feed(
            token: String?,
            lang: String? = "en",
            cursor: String? = null,
            sort: String? = null,
            countryCode: String? = null,
        ): ResultActionsDsl =
            mockMvc.get("/api/reviews") {
                token?.let { header("Authorization", "Bearer $it") }
                lang?.let { param("lang", it) }
                cursor?.let { param("cursor", it) }
                sort?.let { param("sort", it) }
                countryCode?.let { param("countryCode", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("전체 리뷰 목록 — GET /api/reviews (foodId 없음)") {
            `when`("여러 음식의 리뷰 25건에서 첫 페이지를 조회하면") {
                then("음식 구분 없이 최신순 20건과 다음 커서를 주고, 커서로 나머지를 잇는다") {
                    seedFood(900L, "피드김치찌개")
                    seedFood(901L, "피드된장찌개")
                    val token = accessToken(9001L)
                    val reviewIds = (1..25).map { createReview(token, if (it % 2 == 0) 900L else 901L) }

                    val first = payloadOf(feed(token))
                    first.path("items").size() shouldBe 20
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    first.path("items").map { it.path("reviewId").asLong() } shouldBe
                        reviewIds.takeLast(20).sortedDescending()

                    val second = payloadOf(feed(token, cursor = first.path("nextCursor").asLong().toString()))
                    second.path("items").map { it.path("reviewId").asLong() }.take(5) shouldBe
                        reviewIds.take(5).sortedDescending()
                }
            }
            `when`("내가 신고한 리뷰가 있으면") {
                then("내 피드에서만 빠진다") {
                    seedFood(902L, "피드신고음식")
                    val author = accessToken(9002L)
                    val viewer = accessToken(9003L)
                    val reported = createReview(author, 902L)
                    val kept = createReview(author, 902L)
                    mockMvc.post("/api/reports") {
                        header("Authorization", "Bearer $viewer")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(
                            mapOf("targetType" to "REVIEW", "targetId" to reported, "reason" to "SPAM"),
                        )
                    }.andExpect { status { isOk() } }

                    val viewerIds = payloadOf(feed(viewer)).path("items").map { it.path("reviewId").asLong() }
                    viewerIds.contains(kept) shouldBe true
                    viewerIds.contains(reported) shouldBe false

                    val authorIds = payloadOf(feed(author)).path("items").map { it.path("reviewId").asLong() }
                    authorIds.contains(reported) shouldBe true
                }
            }
            `when`("작성자가 탈퇴하면") {
                then("그 작성자의 리뷰가 피드에 남고 author 는 null, authorWithdrawn 이 true 다") {
                    seedFood(908L, "피드탈퇴음식")
                    val writer = accessToken(9008L)
                    val viewer = accessToken(9009L)
                    val withdrawn = createReview(writer, 908L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE member SET status = 'DELETED' WHERE id = 9008") }
                    }

                    val item = payloadOf(feed(viewer)).path("items")
                        .first { it.path("reviewId").asLong() == withdrawn }
                    item.path("author").isNull shouldBe true
                    item.path("authorWithdrawn").asBoolean() shouldBe true
                }
            }
            `when`("내가 차단한 회원의 리뷰가 있으면") {
                then("내 피드에서만 빠진다") {
                    seedFood(909L, "피드차단음식")
                    val author = accessToken(9010L)
                    val viewer = accessToken(9011L)
                    val blocked = createReview(author, 909L)
                    mockMvc.post("/api/members/me/blocks") {
                        header("Authorization", "Bearer $viewer")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("memberId" to 9010L))
                    }.andExpect { status { isOk() } }

                    payloadOf(feed(viewer)).path("items")
                        .map { it.path("reviewId").asLong() }
                        .contains(blocked) shouldBe false
                    payloadOf(feed(author)).path("items")
                        .map { it.path("reviewId").asLong() }
                        .contains(blocked) shouldBe true
                }
            }
            `when`("음식이 소프트 삭제되면") {
                then("그 음식의 리뷰가 피드에서 빠진다") {
                    seedFood(903L, "피드삭제음식")
                    seedFood(904L, "피드생존음식")
                    val token = accessToken(9004L)
                    val orphaned = createReview(token, 903L)
                    val kept = createReview(token, 904L)
                    softDeleteFood(903L)

                    val ids = payloadOf(feed(token)).path("items").map { it.path("reviewId").asLong() }
                    ids.contains(kept) shouldBe true
                    ids.contains(orphaned) shouldBe false
                }
            }
            `when`("리뷰의 음식에 요청 언어 번역과 이미지가 있으면") {
                then("food 객체에 번역 이름과 이미지 URL 이 내려간다") {
                    seedFood(905L, "피드음식정보김밥", imageRef = "images/food/905.jpg")
                    dataSource.connection.use { c ->
                        c.prepareStatement("""UPDATE food SET name_translations = '{"en":"Kimbap"}' WHERE id = 905""")
                            .use { it.executeUpdate() }
                    }
                    val token = accessToken(9007L)
                    val reviewId = createReview(token, 905L)

                    val item = payloadOf(feed(token, lang = "en")).path("items")
                        .single { it.path("reviewId").asLong() == reviewId }
                    item.path("food").path("foodId").asLong() shouldBe 905L
                    item.path("food").path("name").asText() shouldBe "Kimbap"
                    item.path("food").path("imageUrl").asText() shouldBe "https://cdn.test/images/food/905.jpg"
                    item.path("author").path("nickname").asText() shouldBe "피더9007"
                    item.has("foodId") shouldBe false
                    item.has("memberId") shouldBe false
                }
            }
            `when`("X-API-Version 1.0 헤더로 조회하면") {
                then("정상 조회된다") {
                    val token = accessToken(9012L)
                    mockMvc.get("/api/reviews") {
                        header("Authorization", "Bearer $token")
                        header("X-API-Version", "1.0")
                        param("lang", "en")
                    }.andExpect { status { isOk() } }
                }
            }
            `when`("지원하지 않는 버전 헤더로 조회하면") {
                then("400 을 반환한다") {
                    val token = accessToken(9012L)
                    mockMvc.get("/api/reviews") {
                        header("Authorization", "Bearer $token")
                        header("X-API-Version", "9.9")
                        param("lang", "en")
                    }.andExpect { status { isBadRequest() } }
                }
            }
            `when`("lang 없이 조회하면") {
                then("400 을 반환한다") {
                    val token = accessToken(9005L)
                    feed(token, lang = null).andExpect { status { isBadRequest() } }
                }
            }
            `when`("리뷰 수가 다른 음식들의 리뷰를 음식 리뷰 수 많은 순으로 조회하면") {
                then("소속 음식의 리뷰 수 내림차순, 같은 음식 안에서는 최신 우선이다") {
                    seedFood(960L, "정렬피드갈비탕")
                    seedFood(961L, "정렬피드육개장")
                    val a = accessToken(9601L, "ZW")
                    val b = accessToken(9602L, "ZW")
                    val c = accessToken(9603L, "ZW")
                    val f1r1 = createReview(a, 960L)
                    val f1r2 = createReview(b, 960L)
                    val f2r1 = createReview(c, 961L)
                    val f1r3 = createReview(c, 960L)

                    val ids = mapper.readTree(
                        feed(null, sort = "food_review_count", countryCode = "ZW")
                            .andExpect { status { isOk() } }
                            .andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload").path("items").map { it.path("reviewId").asLong() }

                    ids shouldBe listOf(f1r3, f1r2, f1r1, f2r1)
                }
            }
            `when`("토큰 없이 전체 피드를 조회하면") {
                then("200 과 함께 피드가 내려가고 likedByMe 는 전부 false 다") {
                    val guest = mapper.readTree(
                        feed(null).andExpect { status { isOk() } }
                            .andReturn().response.getContentAsString(Charsets.UTF_8),
                    ).path("payload")
                    guest.path("items").size() shouldBeGreaterThan 0
                    guest.path("items").forEach { it.path("likedByMe").asBoolean().shouldBeFalse() }
                }
            }
            `when`("토큰 없이 lang 없이 조회하면") {
                then("비회원도 동일하게 400 이다") {
                    feed(null, lang = null).andExpect { status { isBadRequest() } }
                }
            }
            `when`("비정상 커서로 조회하면") {
                then("400 FOOD-002 를 반환한다") {
                    val token = accessToken(9006L)
                    feed(token, cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
        }
    }
}
