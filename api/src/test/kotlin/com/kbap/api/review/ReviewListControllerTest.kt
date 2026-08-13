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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReviewListControllerTest : BehaviorSpec() {
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
                    ps.setString(2, "review-list-test-$memberId")
                    ps.setString(3, "리뷰어$memberId")
                    ps.setString(4, countryCode)
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long, countryCode: String? = "KR"): String {
            seedMember(memberId, countryCode)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, nameTranslations: String = "{}", imageRef: String? = null): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, image_ref, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.setString(3, imageRef)
                    ps.setString(4, nameTranslations)
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

        fun deleteReview(token: String, reviewId: Long) {
            mockMvc.delete("/api/reviews/$reviewId") {
                header("Authorization", "Bearer $token")
            }.andExpect { status { isOk() } }
        }

        fun foodReviews(
            token: String?,
            foodId: Long,
            cursor: String? = null,
            countryCode: String? = null,
            lang: String? = "en",
        ): ResultActionsDsl =
            mockMvc.get("/api/reviews") {
                token?.let { header("Authorization", "Bearer $it") }
                param("foodId", foodId.toString())
                cursor?.let { param("cursor", it) }
                countryCode?.let { param("countryCode", it) }
                lang?.let { param("lang", it) }
            }

        fun myReviews(token: String?, cursor: String? = null, lang: String? = "en"): ResultActionsDsl =
            mockMvc.get("/api/reviews/me") {
                token?.let { header("Authorization", "Bearer $it") }
                cursor?.let { param("cursor", it) }
                lang?.let { param("lang", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("음식별 리뷰 목록 — GET /api/reviews?foodId=") {
            seedFood(800L, "목록김치찌개")

            `when`("리뷰 25건에서 첫 페이지를 조회하면") {
                then("최신순 20건과 다음 커서를 주고, 커서로 나머지 5건을 잇는다") {
                    val token = accessToken(800L)
                    val reviewIds = (1..25).map { createReview(token, 800L) }

                    val first = payloadOf(foodReviews(token, 800L))
                    first.path("items").size() shouldBe 20
                    first.path("items").first().path("reviewId").asLong() shouldBe reviewIds.last()
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    val nextCursor = first.path("nextCursor").asLong()

                    val second = payloadOf(foodReviews(token, 800L, cursor = nextCursor.toString()))
                    second.path("items").size() shouldBe 5
                    second.path("hasNext").asBoolean().shouldBeFalse()
                    second.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("countryCode 필터로 조회하면") {
                then("작성 시점 국적 스냅샷이 일치하는 리뷰만 준다") {
                    seedFood(801L, "목록된장찌개")
                    val kr = accessToken(801L, "KR")
                    val vn = accessToken(802L, "VN")
                    createReview(kr, 801L)
                    createReview(kr, 801L)
                    val vnReviewId = createReview(vn, 801L)

                    val filtered = payloadOf(foodReviews(kr, 801L, countryCode = "VN"))
                    filtered.path("items").size() shouldBe 1
                    filtered.path("items").first().path("reviewId").asLong() shouldBe vnReviewId

                    payloadOf(foodReviews(kr, 801L)).path("items").size() shouldBe 3
                }
            }
            `when`("리뷰가 없는 국적 코드로 필터하면") {
                then("빈 목록을 준다") {
                    val token = accessToken(800L)
                    payloadOf(foodReviews(token, 800L, countryCode = "ZZ")).path("items").size() shouldBe 0
                }
            }
            `when`("삭제된 리뷰가 있으면") {
                then("목록에서 제외된다") {
                    seedFood(803L, "목록비빔밥")
                    val token = accessToken(803L)
                    val kept = createReview(token, 803L)
                    val deleted = createReview(token, 803L)
                    deleteReview(token, deleted)

                    val items = payloadOf(foodReviews(token, 803L)).path("items")
                    items.size() shouldBe 1
                    items.first().path("reviewId").asLong() shouldBe kept
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 을 반환한다") {
                    foodReviews(null, 800L).andExpect { status { isUnauthorized() } }
                }
            }
            `when`("비정상 커서로 조회하면") {
                then("400 FOOD-002 를 반환한다") {
                    val token = accessToken(800L)
                    foodReviews(token, 800L, cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
            `when`("존재하지 않는 음식으로 조회하면") {
                then("400 FOOD-001 을 반환한다") {
                    val token = accessToken(800L)
                    foodReviews(token, 899L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
            `when`("foodId 없이 조회하면") {
                then("전체 리뷰 목록으로 동작한다") {
                    val token = accessToken(800L)
                    mockMvc.get("/api/reviews") {
                        header("Authorization", "Bearer $token")
                        param("lang", "en")
                    }.andExpect { status { isOk() } }
                }
            }
        }

        given("음식별 리뷰 목록 — 신고한 리뷰 숨김") {
            fun reportReview(token: String, reviewId: Long) {
                mockMvc.post("/api/v1/reports") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(
                        mapOf("targetType" to "REVIEW", "targetId" to reviewId, "reason" to "SPAM"),
                    )
                }.andExpect { status { isOk() } }
            }

            `when`("회원 A 가 리뷰 하나를 신고하면") {
                then("A 의 목록에서만 빠지고 다른 회원 B 의 목록에는 그대로 보인다") {
                    seedFood(830L, "숨김김치찌개")
                    val author = accessToken(8301L)
                    val viewerA = accessToken(8302L)
                    val viewerB = accessToken(8303L)
                    val reported = createReview(author, 830L)
                    val kept = createReview(author, 830L)

                    reportReview(viewerA, reported)

                    payloadOf(foodReviews(viewerA, 830L)).path("items")
                        .map { it.path("reviewId").asLong() } shouldBe listOf(kept)
                    payloadOf(foodReviews(viewerB, 830L)).path("items")
                        .map { it.path("reviewId").asLong() } shouldBe listOf(kept, reported)
                }
            }

            `when`("회원 A 가 한 음식에서 여러 리뷰를 신고하면") {
                then("신고한 리뷰 전부가 A 의 목록에서 빠진다") {
                    seedFood(831L, "숨김된장찌개")
                    val author = accessToken(8311L)
                    val viewer = accessToken(8312L)
                    val reviewIds = (1..4).map { createReview(author, 831L) }

                    reportReview(viewer, reviewIds[0])
                    reportReview(viewer, reviewIds[2])

                    payloadOf(foodReviews(viewer, 831L)).path("items")
                        .map { it.path("reviewId").asLong() } shouldBe listOf(reviewIds[3], reviewIds[1])
                }
            }

            `when`("리뷰 25건 중 3건을 신고하고 페이지를 넘기면") {
                then("신고 리뷰를 뺀 최신순으로 채우고 hasNext·nextCursor 규약을 유지한다") {
                    seedFood(832L, "숨김비빔밥")
                    val author = accessToken(8321L)
                    val viewer = accessToken(8322L)
                    val reviewIds = (1..25).map { createReview(author, 832L) }
                    val reportedIds = listOf(reviewIds[24], reviewIds[12], reviewIds[0])

                    reportedIds.forEach { reportReview(viewer, it) }

                    val first = payloadOf(foodReviews(viewer, 832L))
                    first.path("items").size() shouldBe 20
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    val firstIds = first.path("items").map { it.path("reviewId").asLong() }

                    val second = payloadOf(foodReviews(viewer, 832L, cursor = first.path("nextCursor").asLong().toString()))
                    second.path("items").size() shouldBe 2
                    second.path("hasNext").asBoolean().shouldBeFalse()
                    second.path("nextCursor").isNull.shouldBeTrue()

                    val visibleIds = firstIds + second.path("items").map { it.path("reviewId").asLong() }
                    visibleIds shouldBe reviewIds.filterNot { it in reportedIds }.sortedDescending()
                }
            }
        }

        given("리뷰 목록 — 좋아요 수와 내 좋아요 여부") {
            fun like(token: String, reviewId: Long) {
                mockMvc.post("/api/reviews/$reviewId/like") {
                    header("Authorization", "Bearer $token")
                    param("liked", "true")
                }.andExpect { status { isOk() } }
            }

            fun unlike(token: String, reviewId: Long) {
                mockMvc.post("/api/reviews/$reviewId/like") {
                    header("Authorization", "Bearer $token")
                    param("liked", "false")
                }.andExpect { status { isOk() } }
            }

            `when`("리뷰에 회원 3명이 좋아요를 눌렀으면") {
                then("likeCount 3, 누른 회원에겐 likedByMe true 로 내려간다") {
                    seedFood(840L, "좋아요김치찌개")
                    val author = accessToken(8401L)
                    val viewer = accessToken(8402L)
                    val reviewId = createReview(author, 840L)
                    like(viewer, reviewId)
                    like(accessToken(8403L), reviewId)
                    like(accessToken(8404L), reviewId)

                    val item = payloadOf(foodReviews(viewer, 840L)).path("items").single()
                    item.path("reviewId").asLong() shouldBe reviewId
                    item.path("likeCount").asLong() shouldBe 3L
                    item.path("likedByMe").asBoolean().shouldBeTrue()
                }
            }
            `when`("좋아요한 리뷰와 안 한 리뷰가 섞여 있으면") {
                then("리뷰별로 likedByMe 가 정확히 갈린다") {
                    seedFood(841L, "좋아요된장찌개")
                    val author = accessToken(8411L)
                    val viewer = accessToken(8412L)
                    val liked = createReview(author, 841L)
                    val notLiked = createReview(author, 841L)
                    like(viewer, liked)

                    val byId = payloadOf(foodReviews(viewer, 841L)).path("items").associateBy { it.path("reviewId").asLong() }
                    byId.getValue(liked).path("likedByMe").asBoolean().shouldBeTrue()
                    byId.getValue(liked).path("likeCount").asLong() shouldBe 1L
                    byId.getValue(notLiked).path("likedByMe").asBoolean().shouldBeFalse()
                    byId.getValue(notLiked).path("likeCount").asLong() shouldBe 0L
                }
            }
            `when`("좋아요가 하나도 없는 리뷰를 조회하면") {
                then("필드가 존재하며 likeCount 0·likedByMe false 다") {
                    seedFood(842L, "좋아요비빔밥")
                    val author = accessToken(8421L)
                    createReview(author, 842L)

                    val item = payloadOf(foodReviews(author, 842L)).path("items").single()
                    item.has("likeCount").shouldBeTrue()
                    item.has("likedByMe").shouldBeTrue()
                    item.path("likeCount").asLong() shouldBe 0L
                    item.path("likedByMe").asBoolean().shouldBeFalse()
                }
            }
            `when`("좋아요를 취소하면") {
                then("수와 여부에서 즉시 빠진다") {
                    seedFood(843L, "좋아요불고기")
                    val author = accessToken(8431L)
                    val viewer = accessToken(8432L)
                    val reviewId = createReview(author, 843L)
                    like(viewer, reviewId)
                    unlike(viewer, reviewId)

                    val item = payloadOf(foodReviews(viewer, 843L)).path("items").single()
                    item.path("likeCount").asLong() shouldBe 0L
                    item.path("likedByMe").asBoolean().shouldBeFalse()
                }
            }
            `when`("내 리뷰 목록을 조회하면") {
                then("동일하게 likeCount·likedByMe 가 포함된다") {
                    seedFood(844L, "좋아요잡채")
                    val author = accessToken(8441L)
                    val other = accessToken(8442L)
                    val reviewId = createReview(author, 844L)
                    like(other, reviewId)
                    like(author, reviewId)

                    val item = payloadOf(myReviews(author)).path("items").single()
                    item.path("reviewId").asLong() shouldBe reviewId
                    item.path("likeCount").asLong() shouldBe 2L
                    item.path("likedByMe").asBoolean().shouldBeTrue()
                }
            }
        }

        given("리뷰 목록 — 음식 정보(food) 포함") {
            `when`("번역이 있는 언어로 조회하면") {
                then("food.name 은 해당 언어 번역, imageUrl 은 음식 대표 이미지다") {
                    seedFood(850L, "음식정보김치찌개", """{"vi":"Canh kimchi"}""", "images/food/850.jpg")
                    val token = accessToken(8501L)
                    val reviewId = createReview(token, 850L)

                    val item = payloadOf(foodReviews(token, 850L, lang = "vi")).path("items")
                        .single { it.path("reviewId").asLong() == reviewId }
                    item.path("food").path("foodId").asLong() shouldBe 850L
                    item.path("food").path("name").asText() shouldBe "Canh kimchi"
                    item.path("food").path("imageUrl").asText() shouldBe "https://cdn.test/images/food/850.jpg"
                }
            }
            `when`("번역이 없는 지원 언어로 조회하면") {
                then("food.name 은 한국어 원문으로 폴백한다") {
                    seedFood(851L, "음식정보된장찌개", """{"vi":"Canh tương"}""")
                    val token = accessToken(8502L)
                    createReview(token, 851L)

                    val item = payloadOf(foodReviews(token, 851L, lang = "ja")).path("items").first()
                    item.path("food").path("name").asText() shouldBe "음식정보된장찌개"
                    item.path("food").path("imageUrl").isNull.shouldBeTrue()
                }
            }
            `when`("지원 목록에 없는 lang 으로 조회하면") {
                then("food.name 은 영어 번역으로 폴백한다") {
                    seedFood(852L, "음식정보비빔밥", """{"en":"Bibimbap"}""")
                    val token = accessToken(8503L)
                    createReview(token, 852L)

                    val item = payloadOf(foodReviews(token, 852L, lang = "fr")).path("items").first()
                    item.path("food").path("name").asText() shouldBe "Bibimbap"
                }
            }
            `when`("내 리뷰 목록을 조회하면") {
                then("동일하게 food 객체가 포함된다") {
                    seedFood(853L, "음식정보불고기", """{"en":"Bulgogi"}""")
                    val token = accessToken(8504L)
                    val reviewId = createReview(token, 853L)

                    val item = payloadOf(myReviews(token, lang = "en")).path("items")
                        .single { it.path("reviewId").asLong() == reviewId }
                    item.path("food").path("foodId").asLong() shouldBe 853L
                    item.path("food").path("name").asText() shouldBe "Bulgogi"
                }
            }
            `when`("내 리뷰의 음식이 소프트 삭제되면") {
                then("그 리뷰의 food 는 null 이고 리뷰 자체는 보인다") {
                    seedFood(854L, "음식정보삭제음식")
                    val token = accessToken(8505L)
                    val reviewId = createReview(token, 854L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE food SET status = 'DELETED' WHERE id = 854") }
                    }

                    val item = payloadOf(myReviews(token)).path("items")
                        .single { it.path("reviewId").asLong() == reviewId }
                    item.path("food").isNull.shouldBeTrue()
                }
            }
            `when`("lang 없이 음식별 목록을 조회하면") {
                then("400 을 반환한다") {
                    val token = accessToken(8506L)
                    foodReviews(token, 850L, lang = null).andExpect { status { isBadRequest() } }
                }
            }
            `when`("lang 없이 내 리뷰 목록을 조회하면") {
                then("400 을 반환한다") {
                    val token = accessToken(8506L)
                    myReviews(token, lang = null).andExpect { status { isBadRequest() } }
                }
            }
        }

        given("내 리뷰 목록 — GET /api/reviews/me") {
            seedFood(810L, "목록불고기")
            seedFood(811L, "목록잡채")

            `when`("여러 회원의 리뷰가 섞여 있으면") {
                then("본인 리뷰만 최신순으로 준다") {
                    val mine = accessToken(810L)
                    val other = accessToken(811L)
                    val first = createReview(mine, 810L)
                    val second = createReview(mine, 811L)
                    createReview(other, 810L)

                    val items = payloadOf(myReviews(mine)).path("items")
                    items.size() shouldBe 2
                    items.map { it.path("reviewId").asLong() } shouldBe listOf(second, first)
                    items.first().path("author").path("nickname").asText() shouldBe "리뷰어810"
                    items.first().path("author").path("countryCode").asText() shouldBe "KR"
                }
            }
            `when`("탈퇴한 회원의 리뷰가 목록에 있으면") {
                then("리뷰가 노출되고 author 는 null, authorWithdrawn 이 true 로 내려간다") {
                    seedFood(820L, "목록탈퇴음식")
                    val writer = accessToken(820L)
                    val viewer = accessToken(821L)
                    val kept = createReview(viewer, 820L)
                    val withdrawn = createReview(writer, 820L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE member SET status = 'DELETED' WHERE id = 820") }
                    }

                    val items = payloadOf(foodReviews(viewer, 820L)).path("items")
                    items.size() shouldBe 2
                    val byId = items.associateBy { it.path("reviewId").asLong() }
                    byId.getValue(withdrawn).path("author").isNull shouldBe true
                    byId.getValue(withdrawn).path("authorWithdrawn").asBoolean() shouldBe true
                    byId.getValue(kept).path("author").isNull shouldBe false
                    byId.getValue(kept).path("authorWithdrawn").asBoolean() shouldBe false
                }
            }
            `when`("내 리뷰를 삭제하면") {
                then("목록에서 빠진다") {
                    val token = accessToken(812L)
                    val reviewId = createReview(token, 810L)
                    deleteReview(token, reviewId)
                    payloadOf(myReviews(token)).path("items").size() shouldBe 0
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 을 반환한다") {
                    myReviews(null).andExpect { status { isUnauthorized() } }
                }
            }
        }

        given("리뷰 목록의 식당 정보 노출") {
            seedFood(840L, "목록순두부")

            fun createReviewWithPlace(token: String, foodId: Long): Long {
                val response = mockMvc.post("/api/v1/reviews") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(
                        mapOf(
                            "foodId" to foodId,
                            "rating" to 4,
                            "place" to mapOf(
                                "name" to "한밥집 강남점",
                                "address" to "서울 강남구 테헤란로 123",
                                "kakaoPlaceId" to "27290047",
                                "latitude" to 37.4979502,
                                "longitude" to 127.0276368,
                            ),
                        ),
                    )
                }.andReturn().response.getContentAsString(Charsets.UTF_8)
                return mapper.readTree(response).path("payload").path("reviewId").asLong()
            }

            `when`("식당 정보가 있는 리뷰와 없는 리뷰를 음식별 목록으로 조회하면") {
                then("있는 쪽만 식당 정보를 함께 준다") {
                    val token = accessToken(840L)
                    val withPlace = createReviewWithPlace(token, 840L)
                    val withoutPlace = createReview(token, 840L)

                    val items = payloadOf(foodReviews(token, 840L)).path("items")
                    val byId = items.associateBy { it.path("reviewId").asLong() }

                    byId.getValue(withPlace).path("place").path("name").asText() shouldBe "한밥집 강남점"
                    byId.getValue(withPlace).path("place").path("address").asText() shouldBe "서울 강남구 테헤란로 123"
                    byId.getValue(withPlace).path("place").path("kakaoPlaceId").asText() shouldBe "27290047"
                    byId.getValue(withoutPlace).path("place").isNull.shouldBeTrue()
                }
            }

            `when`("내 리뷰 목록으로 조회하면") {
                then("식당 정보가 함께 내려간다") {
                    val token = accessToken(841L)
                    createReviewWithPlace(token, 840L)

                    val items = payloadOf(myReviews(token)).path("items")
                    items.size() shouldBe 1
                    items.first().path("place").path("name").asText() shouldBe "한밥집 강남점"
                }
            }
        }
    }
}
