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
        afterSpec {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM scan_history") } }
        }

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

        fun seedScanOfAllFoods(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO scan_history (member_id, price, food_id, status, created_at, updated_at)
                    SELECT ?, NULL, id, 'ACTIVE', NOW(6), NOW(6) FROM food
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long, countryCode: String? = "KR"): String {
            seedMember(memberId, countryCode)
            seedScanOfAllFoods(memberId)
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
            sort: String? = null,
            minRating: Int? = null,
            maxRating: Int? = null,
        ): ResultActionsDsl =
            mockMvc.get("/api/reviews") {
                token?.let { header("Authorization", "Bearer $it") }
                param("foodId", foodId.toString())
                cursor?.let { param("cursor", it) }
                countryCode?.let { param("countryCode", it) }
                lang?.let { param("lang", it) }
                sort?.let { param("sort", it) }
                minRating?.let { param("minRating", it.toString()) }
                maxRating?.let { param("maxRating", it.toString()) }
            }

        fun likeReview(token: String, reviewId: Long) {
            mockMvc.post("/api/reviews/$reviewId/like") {
                header("Authorization", "Bearer $token")
                param("liked", "true")
            }.andExpect { status { isOk() } }
        }

        fun myReviews(token: String?, cursor: String? = null, lang: String? = "en"): ResultActionsDsl =
            mockMvc.get("/api/reviews/me") {
                token?.let { header("Authorization", "Bearer $it") }
                cursor?.let { param("cursor", it) }
                lang?.let { param("lang", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun reviewIdsOf(result: ResultActionsDsl): List<Long> =
            payloadOf(result).path("items").map { it.path("reviewId").asLong() }

        fun traverseAll(foodId: Long, sort: String?): List<Long> {
            val collected = mutableListOf<Long>()
            var cursor: String? = null
            repeat(10) {
                val response = foodReviews(null, foodId, cursor = cursor, sort = sort).andReturn().response
                check(response.status == 200) {
                    "순회 중 HTTP ${response.status} (cursor=$cursor): ${response.getContentAsString(Charsets.UTF_8)}"
                }
                val payload = mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("payload")
                collected += payload.path("items").map { it.path("reviewId").asLong() }
                cursor = payload.path("nextCursor").textValue() ?: return collected
            }
            error("페이지 순회가 10회를 초과했다 — 커서가 전진하지 않는다 (마지막 커서: $cursor)")
        }

        given("리뷰 목록 정렬 — GET /api/reviews?sort=") {
            `when`("평점이 다른 리뷰들을 평점 높은 순으로 조회하면") {
                then("별점 내림차순, 동점은 최신 우선이다") {
                    seedFood(860L, "정렬김치찌개")
                    val r1 = createReview(accessToken(8601L), 860L, rating = 2)
                    val r2 = createReview(accessToken(8602L), 860L, rating = 5)
                    val r3 = createReview(accessToken(8603L), 860L, rating = 3)
                    val r4 = createReview(accessToken(8604L), 860L, rating = 5)
                    val r5 = createReview(accessToken(8605L), 860L, rating = 1)

                    reviewIdsOf(foodReviews(null, 860L, sort = "rating_high")) shouldBe
                        listOf(r4, r2, r3, r1, r5)
                }
            }
            `when`("같은 리뷰들을 평점 낮은 순으로 조회하면") {
                then("별점 오름차순, 동점은 최신 우선이다") {
                    val ids = reviewIdsOf(foodReviews(null, 860L, sort = "rating_low"))
                    payloadOf(foodReviews(null, 860L, sort = "rating_low")).path("items")
                        .map { it.path("rating").asInt() } shouldBe listOf(1, 2, 3, 5, 5)
                    ids.take(3) shouldBe reviewIdsOf(foodReviews(null, 860L, sort = "rating_high")).takeLast(3).reversed()
                }
            }
            `when`("좋아요 수가 다른 리뷰들을 helpful 내림차순으로 조회하면") {
                then("좋아요 수 내림차순, 동점은 최신 우선이다") {
                    seedFood(861L, "정렬된장찌개")
                    val r1 = createReview(accessToken(8611L), 861L, rating = 4)
                    val r2 = createReview(accessToken(8612L), 861L, rating = 4)
                    val r3 = createReview(accessToken(8613L), 861L, rating = 4)
                    likeReview(accessToken(8611L), r2)
                    likeReview(accessToken(8612L), r2)
                    likeReview(accessToken(8613L), r1)

                    reviewIdsOf(foodReviews(null, 861L, sort = "helpful")) shouldBe
                        listOf(r2, r1, r3)
                }
            }
            `when`("sort 를 생략하면") {
                then("기존과 동일한 최신순이다") {
                    val ids = reviewIdsOf(foodReviews(null, 861L))
                    ids shouldBe ids.sortedDescending()
                }
            }
            `when`("허용값 밖 sort 로 조회하면") {
                then("대문자 표기를 포함해 400 COMMON-002 를 반환한다") {
                    listOf("RANDOM", "HELPFUL_DESC", "Rating_High", "rating", "desc").forEach { invalid ->
                        foodReviews(null, 861L, sort = invalid).andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                    }
                }
            }
        }

        given("정렬별 커서 페이징 정합 — 동점 경계") {
            `when`("전부 동점(별점 4)인 리뷰 25건을 평점 높은 순으로 끝까지 페이징하면") {
                then("중복·누락 없이 모든 리뷰가 정확히 한 번씩 최신순으로 나온다") {
                    seedFood(863L, "페이징감자탕")
                    val created = (1..55).map { createReview(accessToken(8630L + it), 863L, rating = 4) }

                    val first = payloadOf(foodReviews(null, 863L, sort = "rating_high"))
                    first.path("items").size() shouldBe 50
                    first.path("hasNext").asBoolean().shouldBeTrue()

                    val all = traverseAll(863L, "rating_high")
                    all shouldBe created.sortedDescending()
                }
            }
            `when`("좋아요가 섞인 같은 목록을 helpful 내림차순으로 끝까지 페이징하면") {
                then("좋아요 수 우선·동점 최신순으로 중복·누락 없이 나온다") {
                    val created = traverseAll(863L, null)
                    val liked2 = created[10]
                    val liked1 = created[20]
                    likeReview(accessToken(8631L), liked2)
                    likeReview(accessToken(8632L), liked2)
                    likeReview(accessToken(8633L), liked1)

                    val all = traverseAll(863L, "helpful")
                    all.size shouldBe 55
                    all.toSet() shouldBe created.toSet()
                    all.take(2) shouldBe listOf(liked2, liked1)
                }
            }
            `when`("LATEST 형식 커서를 지표 정렬에 재사용하면") {
                then("400 FOOD-002 를 반환한다") {
                    foodReviews(null, 863L, cursor = "42", sort = "rating_high").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
        }

        given("리뷰 목록 별점 구간 필터 — GET /api/reviews?minRating=&maxRating=") {
            `when`("별점 1~5 리뷰에서 1~3점 구간으로 조회하면") {
                then("구간 안 리뷰만 최신순으로 내려간다") {
                    seedFood(862L, "필터부대찌개")
                    val r1 = createReview(accessToken(8621L, "KR"), 862L, rating = 1)
                    val r2 = createReview(accessToken(8622L, "VN"), 862L, rating = 2)
                    val r3 = createReview(accessToken(8623L, "KR"), 862L, rating = 3)
                    createReview(accessToken(8624L, "VN"), 862L, rating = 4)
                    createReview(accessToken(8625L, "KR"), 862L, rating = 5)

                    reviewIdsOf(foodReviews(null, 862L, minRating = 1, maxRating = 3)) shouldBe
                        listOf(r3, r2, r1)
                }
            }
            `when`("하한=상한=3 으로 조회하면") {
                then("3점 리뷰만 내려간다") {
                    val items = payloadOf(foodReviews(null, 862L, minRating = 3, maxRating = 3)).path("items")
                    items.size() shouldBe 1
                    items.first().path("rating").asInt() shouldBe 3
                }
            }
            `when`("별점 구간과 국적 필터·정렬을 함께 지정하면") {
                then("전 조건의 교집합이 정렬 순서로 내려간다") {
                    val ids = reviewIdsOf(
                        foodReviews(null, 862L, countryCode = "KR", minRating = 2, maxRating = 5, sort = "rating_low"),
                    )
                    payloadOf(
                        foodReviews(null, 862L, countryCode = "KR", minRating = 2, maxRating = 5, sort = "rating_low"),
                    ).path("items").map { it.path("rating").asInt() } shouldBe listOf(3, 5)
                    ids.size shouldBe 2
                }
            }
            `when`("하한이 상한보다 크면") {
                then("400 COMMON-002 를 반환한다") {
                    foodReviews(null, 862L, minRating = 4, maxRating = 2).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
            `when`("범위 밖 별점(0·6)으로 조회하면") {
                then("400 COMMON-002 를 반환한다") {
                    foodReviews(null, 862L, minRating = 0).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                    foodReviews(null, 862L, maxRating = 6).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
        }

        given("음식별 리뷰 목록 — GET /api/reviews?foodId=") {
            seedFood(800L, "목록김치찌개")

            `when`("리뷰 25건에서 첫 페이지를 조회하면") {
                then("최신순 20건과 다음 커서를 주고, 커서로 나머지 5건을 잇는다") {
                    val token = accessToken(800L)
                    val reviewIds = (1..55).map { createReview(token, 800L) }

                    val first = payloadOf(foodReviews(token, 800L))
                    first.path("items").size() shouldBe 50
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
                then("회원과 동일한 목록이 내려가고 likedByMe 는 전부 false 다") {
                    accessToken(800L)
                    val guest = payloadOf(foodReviews(null, 800L))
                    guest.path("items").size() shouldBe 50
                    guest.path("hasNext").asBoolean().shouldBeTrue()
                    guest.path("items").forEach { it.path("likedByMe").asBoolean().shouldBeFalse() }

                    val member = payloadOf(foodReviews(accessToken(800L), 800L))
                    guest.path("items").first().path("reviewId").asLong() shouldBe
                        member.path("items").first().path("reviewId").asLong()
                }
            }
            `when`("토큰 없이 커서로 다음 페이지를 조회하면") {
                then("keyset 페이징이 회원과 동일하게 동작한다") {
                    val first = payloadOf(foodReviews(null, 800L))
                    val nextCursor = first.path("nextCursor").asLong()
                    val second = payloadOf(foodReviews(null, 800L, cursor = nextCursor.toString()))
                    second.path("items").size() shouldBe 5
                    second.path("hasNext").asBoolean().shouldBeFalse()
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
                mockMvc.post("/api/reports") {
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
                    val reviewIds = (1..55).map { createReview(author, 832L) }
                    val reportedIds = listOf(reviewIds[24], reviewIds[12], reviewIds[0])

                    reportedIds.forEach { reportReview(viewer, it) }

                    val first = payloadOf(foodReviews(viewer, 832L))
                    first.path("items").size() shouldBe 50
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
                then("그 리뷰의 food 필드는 생략되고 리뷰 자체는 보인다") {
                    seedFood(854L, "음식정보삭제음식")
                    val token = accessToken(8505L)
                    val reviewId = createReview(token, 854L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE food SET status = 'DELETED' WHERE id = 854") }
                    }

                    val item = payloadOf(myReviews(token)).path("items")
                        .single { it.path("reviewId").asLong() == reviewId }
                    item.has("food").shouldBeFalse()
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

        given("리뷰 목록 — 세부 평가 노출") {
            seedFood(866L, "목록세부평가찌개")

            fun createReviewWithExtras(token: String, foodId: Long, servingSpeed: Int, staffKindness: Int): Long {
                val response = mockMvc.post("/api/reviews") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(
                        mapOf("foodId" to foodId, "rating" to 4, "servingSpeed" to servingSpeed, "staffKindness" to staffKindness),
                    )
                }.andReturn().response.getContentAsString(Charsets.UTF_8)
                return mapper.readTree(response).path("payload").path("reviewId").asLong()
            }

            `when`("세부 평가가 있는 리뷰와 없는 리뷰를 목록으로 조회하면") {
                then("저장한 값은 그대로, 없는 리뷰는 0 으로 내려간다") {
                    val token = accessToken(8601L)
                    createReviewWithExtras(token, 866L, 5, 2)
                    val plain = createReview(token, 866L)

                    val items = payloadOf(foodReviews(token, 866L)).path("items")
                    items.size() shouldBe 2
                    items.first().path("reviewId").asLong() shouldBe plain
                    items.first().path("servingSpeed").asInt() shouldBe 0
                    items.first().path("staffKindness").asInt() shouldBe 0
                    items.last().path("servingSpeed").asInt() shouldBe 5
                    items.last().path("staffKindness").asInt() shouldBe 2
                }
            }
            `when`("내 리뷰 목록을 조회하면") {
                then("세부 평가 값이 동일하게 내려간다") {
                    val token = accessToken(8602L)
                    createReviewWithExtras(token, 866L, 1, 5)
                    val items = payloadOf(myReviews(token)).path("items")
                    items.first().path("servingSpeed").asInt() shouldBe 1
                    items.first().path("staffKindness").asInt() shouldBe 5
                }
            }
        }

        given("리뷰 목록의 식당 정보 노출") {
            seedFood(840L, "목록순두부")

            fun createReviewWithPlace(token: String, foodId: Long): Long {
                val response = mockMvc.post("/api/reviews") {
                    header("Authorization", "Bearer $token")
                    contentType = MediaType.APPLICATION_JSON
                    content = mapper.writeValueAsString(
                        mapOf(
                            "foodId" to foodId,
                            "rating" to 4,
                            "place" to mapOf(
                                "name" to "한밥집 강남점",
                                "address" to "서울 강남구 테헤란로 123",
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
