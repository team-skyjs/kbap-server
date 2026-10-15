package com.kbap.api.review

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
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ReviewDetailControllerTest : BehaviorSpec() {
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

        fun execute(sql: String): Unit =
            dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun seedMember(memberId: Long) {
            execute(
                """
                INSERT INTO member (id, provider, provider_uid, nickname, country_code, member_status,
                                    onboarding_completed, status, created_at, updated_at)
                VALUES ($memberId, 'GOOGLE', 'review-detail-test-$memberId', '상세$memberId', 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
            )
            execute(
                """
                INSERT INTO scan_history (member_id, price, food_id, status, created_at, updated_at)
                SELECT $memberId, NULL, id, 'ACTIVE', NOW(6), NOW(6) FROM food
                """,
            )
        }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, nameTranslations: String) {
            execute(
                """
                INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                  description_translations, ingredients, content_status, status,
                                  created_at, updated_at)
                VALUES ($id, '$koreanName', '설명', 0, '$nameTranslations', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                ON DUPLICATE KEY UPDATE id = id
                """,
            )
        }

        fun createReview(token: String, foodId: Long): Long {
            val response = mockMvc.post("/api/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to 4, "content" to "국물이 진해요"))
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("reviewId").asLong()
        }

        fun getReview(path: String, token: String? = null, lang: String? = "en"): MockHttpServletResponse =
            mockMvc.get(path) {
                token?.let { header("Authorization", "Bearer $it") }
                lang?.let { param("lang", it) }
            }.andReturn().response

        fun MockHttpServletResponse.json(): JsonNode = mapper.readTree(getContentAsString(Charsets.UTF_8))

        fun MockHttpServletResponse.shouldBeError(code: String) {
            status shouldBe 400
            json().path("success").asBoolean().shouldBeFalse()
            json().path("code").asText() shouldBe code
        }

        lateinit var writerToken: String
        lateinit var viewerToken: String
        var reviewId = 0L

        beforeSpec {
            seedFood(98020L, "상세설렁탕", """{"en":"Seolleongtang"}""")
            seedFood(98021L, "상세삭제음식", """{"en":"Deleted Food"}""")
            writerToken = accessToken(98031L)
            viewerToken = accessToken(98032L)
            reviewId = createReview(writerToken, 98020L)
        }

        given("공개된 리뷰 한 건") {
            `when`("비회원이 토큰 없이 lang=en 으로 조회하면") {
                then("200 이고 likedByMe=false, 음식 요약에 한국어명이 붙는다") {
                    val res = getReview("/api/reviews/$reviewId")
                    res.status shouldBe 200
                    val payload = res.json().path("payload")
                    payload.path("reviewId").asLong() shouldBe reviewId
                    payload.path("likedByMe").asBoolean().shouldBeFalse()
                    payload.path("content").asText() shouldBe "국물이 진해요"
                    payload.path("food").path("foodId").asLong() shouldBe 98020L
                    payload.path("food").path("name").asText() shouldBe "Seolleongtang"
                    payload.path("food").path("koreanName").asText() shouldBe "상세설렁탕"
                }
            }
            `when`("lang=ko 로 조회하면") {
                then("food.name 이 이미 한국어라 koreanName 은 null 이다") {
                    val food = getReview("/api/reviews/$reviewId", lang = "ko").json().path("payload").path("food")
                    food.path("name").asText() shouldBe "상세설렁탕"
                    food.has("koreanName").shouldBeTrue()
                    food.path("koreanName").isNull.shouldBeTrue()
                }
            }
            `when`("좋아요를 누른 회원이 조회하면") {
                then("likedByMe=true 이고 likeCount 가 1 이다") {
                    mockMvc.post("/api/reviews/$reviewId/like") {
                        header("Authorization", "Bearer $viewerToken")
                        param("liked", "true")
                    }.andExpect { status { isOk() } }
                    val payload = getReview("/api/reviews/$reviewId", viewerToken).json().path("payload")
                    payload.path("likedByMe").asBoolean().shouldBeTrue()
                    payload.path("likeCount").asLong() shouldBe 1L
                }
            }
            `when`("같은 lang 으로 목록과 단건을 조회하면") {
                then("필드 집합과 값이 목록 항목과 같고, 목록에도 koreanName 이 있다") {
                    val detail = getReview("/api/reviews/$reviewId", viewerToken).json().path("payload")
                    val item = mockMvc.get("/api/reviews") {
                        header("Authorization", "Bearer $viewerToken")
                        param("foodId", "98020")
                        param("lang", "en")
                    }.andReturn().response.json().path("payload").path("items")
                        .first { it.path("reviewId").asLong() == reviewId }
                    detail shouldBe item
                    item.path("food").path("koreanName").asText() shouldBe "상세설렁탕"
                }
            }
        }

        given("볼 수 없는 리뷰") {
            `when`("존재하지 않는 id 로 조회하면") {
                then("400 REVIEW-001") {
                    getReview("/api/reviews/987654321").shouldBeError("REVIEW-001")
                }
            }
            `when`("삭제된 리뷰를 조회하면") {
                then("400 REVIEW-001") {
                    val deletedId = createReview(writerToken, 98020L)
                    mockMvc.delete("/api/reviews/$deletedId") {
                        header("Authorization", "Bearer $writerToken")
                    }.andExpect { status { isOk() } }
                    getReview("/api/reviews/$deletedId").shouldBeError("REVIEW-001")
                    getReview("/api/reviews/$deletedId", writerToken).shouldBeError("REVIEW-001")
                }
            }
            `when`("조회자가 작성자를 차단했으면") {
                then("조회자에겐 REVIEW-001, 작성자 본인과 비회원에겐 200") {
                    val blockerToken = accessToken(98033L)
                    mockMvc.post("/api/members/me/blocks") {
                        header("Authorization", "Bearer $blockerToken")
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("memberId" to 98031L))
                    }.andExpect { status { isOk() } }
                    getReview("/api/reviews/$reviewId", blockerToken).shouldBeError("REVIEW-001")
                    getReview("/api/reviews/$reviewId", writerToken).status shouldBe 200
                    getReview("/api/reviews/$reviewId").status shouldBe 200
                }
            }
            `when`("음식이 삭제된 리뷰를 조회하면") {
                then("타인에겐 REVIEW-001, 작성자 본인에겐 200 이고 food 필드가 생략된다") {
                    val orphanId = createReview(writerToken, 98021L)
                    execute("UPDATE food SET status = 'DELETED' WHERE id = 98021")
                    getReview("/api/reviews/$orphanId", viewerToken).shouldBeError("REVIEW-001")
                    getReview("/api/reviews/$orphanId").shouldBeError("REVIEW-001")
                    val own = getReview("/api/reviews/$orphanId", writerToken)
                    own.status shouldBe 200
                    own.json().path("payload").has("food").shouldBeFalse()
                }
            }
        }

        given("잘못된 요청") {
            `when`("lang 이 없으면") {
                then("400 COMMON-002") {
                    getReview("/api/reviews/$reviewId", lang = null).shouldBeError("COMMON-002")
                }
            }
            `when`("회원이 숫자가 아닌 reviewId 로 조회하면") {
                then("400 COMMON-002") {
                    getReview("/api/reviews/abc", viewerToken).shouldBeError("COMMON-002")
                }
            }
            `when`("비회원이 숫자가 아닌 reviewId 로 조회하면") {
                then("게스트 허용 경로가 아니라 401 이다") {
                    getReview("/api/reviews/abc").status shouldBe 401
                }
            }
        }

        given("내 리뷰 경로") {
            `when`("비회원이 /api/reviews/me 를 조회하면") {
                then("단건 경로로 가지 않고 여전히 401 이다") {
                    getReview("/api/reviews/me").status shouldBe 401
                }
            }
            `when`("회원이 /api/reviews/me 를 조회하면") {
                then("내 리뷰 목록 페이지가 오고 음식 요약에 koreanName 이 있다") {
                    val payload = getReview("/api/reviews/me", writerToken).json().path("payload")
                    payload.has("items").shouldBeTrue()
                    payload.path("items").first { it.path("reviewId").asLong() == reviewId }
                        .path("food").path("koreanName").asText() shouldBe "상세설렁탕"
                }
            }
        }
    }
}
