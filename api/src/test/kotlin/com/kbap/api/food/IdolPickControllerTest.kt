package com.kbap.api.food

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.home.HomeTestSeed
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class IdolPickControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        val itemKeys = setOf(
            "foodId", "name", "koreanName", "imageRef", "spiciness", "overallRiskStatus", "publishedAt", "bookmarked", "review", "video",
        )
        val forbiddenKeys = setOf("title", "thumbnail", "idol", "artist", "group", "reason", "hype", "position", "publishedMonth")

        fun call(path: String, version: String = "1.1", memberId: Long? = null): Pair<Int, JsonNode> {
            val response = mockMvc.get(path) {
                header("X-API-Version", version)
                memberId?.let { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}") }
            }.andReturn().response
            return response.status to mapper.readTree(response.getContentAsString(Charsets.UTF_8))
        }

        fun idolPicks(memberId: Long? = null) = call("/api/foods/idol-picks?lang=en", memberId = memberId).second.path("payload")

        fun JsonNode.ids() = path("items").map { it.path("foodId").asLong() }

        fun JsonNode.keys() = fieldNames().asSequence().toSet()

        fun JsonNode.allFieldNames(): Set<String> = buildSet {
            fun walk(node: JsonNode) {
                if (node.isObject) node.properties().forEach { (key, value) -> add(key); walk(value) }
                if (node.isArray) node.forEach(::walk)
            }
            walk(this@allFieldNames)
        }

        fun video(foodId: Long, hype: Int, month: String, position: Int = 1, platform: String = "YOUTUBE", status: String = "ACTIVE") =
            HomeTestSeed.seedIdolVideo(dataSource, foodId, hype, month, position, platform, status)

        beforeContainer { HomeTestSeed.reset(dataSource) }

        given("화제 영상 K-푸드 목록 — 기본") {
            `when`("매핑이 없으면") {
                then("items 가 빈 배열이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)

                    val payload = idolPicks()
                    payload.keys() shouldBe setOf("items")
                    payload.path("items").isArray shouldBe true
                    payload.path("items").size() shouldBe 0
                }
            }

            `when`("lang 이 없으면") {
                then("400 COMMON-002 다") {
                    val (status, body) = call("/api/foods/idol-picks")
                    status shouldBe 400
                    body.path("code").asText() shouldBe "COMMON-002"
                }
            }
        }

        given("화제 영상 K-푸드 목록 — 정렬과 대표 영상") {
            `when`("화제성·게시월·position 이 섞여 있으면") {
                then("대표 영상 hype 내림차순 → 게시월 내림차순 → foodId 오름차순이고 음식당 1번이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 5)
                    video(1L, 2, "2026-05-01")
                    video(2L, 4, "2025-01-01")
                    video(3L, 4, "2025-06-01")
                    video(4L, 2, "2026-05-01")
                    video(5L, 1, "2026-09-01")
                    video(5L, 5, "2026-09-01", position = 2)
                    video(3L, 1, "2020-01-01", position = 2, platform = "TIKTOK")

                    val payload = idolPicks()
                    payload.ids() shouldContainExactly listOf(3L, 2L, 1L, 4L, 5L)
                    val third = payload.path("items").first()
                    third.path("video").path("url").asText() shouldBe "https://example.com/v/3-1"
                    third.path("video").path("platform").asText() shouldBe "YOUTUBE"
                    payload.path("items").last().path("video").path("url").asText() shouldBe "https://example.com/v/5-1"
                }
            }
        }

        given("화제 영상 K-푸드 목록 — 제외 조건") {
            `when`("비READY 음식·삭제 음식·DELETED 행이 있으면") {
                then("목록에서 빠지고 DELETED 행은 대표 선정에도 쓰이지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 5)
                    video(1L, 3, "2026-01-01")
                    video(2L, 5, "2026-01-01")
                    HomeTestSeed.updateFood(dataSource, 2L, "content_status = 'PENDING_REVIEW'")
                    video(3L, 5, "2026-01-01")
                    HomeTestSeed.updateFood(dataSource, 3L, "status = 'DELETED'")
                    video(4L, 5, "2026-01-01", status = "DELETED")
                    video(5L, 5, "2026-01-01", status = "DELETED")
                    video(5L, 1, "2026-01-01", position = 2, platform = "TIKTOK")

                    val payload = idolPicks()
                    payload.ids() shouldContainExactly listOf(1L, 5L)
                    payload.path("items").last().path("video").path("platform").asText() shouldBe "TIKTOK"
                }
            }
        }

        given("화제 영상 K-푸드 목록 — 회원·비회원") {
            `when`("기피 성분·북마크가 있는 회원과 비회원이 조회하면") {
                then("비회원도 200 이고 같은 목록이며 위험도·북마크는 회원 기준이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))
                    HomeTestSeed.seedFoodSubstance(dataSource, 1L, "EGG", 80)
                    HomeTestSeed.seedBookmark(dataSource, 11L, 2L)
                    video(1L, 4, "2026-01-01")
                    video(2L, 3, "2026-01-01")

                    val (status, guestBody) = call("/api/foods/idol-picks?lang=en")
                    status shouldBe 200
                    val guest = guestBody.path("payload")
                    val member = idolPicks(11L)
                    guest.ids() shouldContainExactly listOf(1L, 2L)
                    member.ids() shouldContainExactly listOf(1L, 2L)
                    guest.path("items").map { it.path("bookmarked").asBoolean() } shouldContainExactly listOf(false, false)
                    member.path("items").map { it.path("bookmarked").asBoolean() } shouldContainExactly listOf(false, true)
                    member.path("items").first().path("overallRiskStatus").asText() shouldBe "DANGER"
                    guest.path("items").first().path("overallRiskStatus").asText() shouldBe "SAFE"
                }
            }
        }

        given("R-19 비노출 보증") {
            `when`("목록 응답 키를 보면") {
                then("payload·항목·video 키 집합이 계약과 정확히 같다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)
                    video(1L, 4, "2026-01-01")
                    video(1L, 2, "2026-01-01", position = 2, platform = "TIKTOK")
                    video(2L, 3, "2026-01-01")

                    val payload = idolPicks()
                    payload.keys() shouldBe setOf("items")
                    payload.path("items").size() shouldBe 2
                    payload.path("items").forEach { item ->
                        item.keys() shouldBe itemKeys
                        item.path("video").keys() shouldBe setOf("url", "platform")
                        item.path("review").keys() shouldBe setOf("averageRating", "count")
                    }
                }
            }

            `when`("목록 응답 본문 전체를 훑으면") {
                then("금지 키가 어떤 깊이에도 없다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    video(1L, 4, "2026-01-01")

                    val (_, body) = call("/api/foods/idol-picks?lang=en")
                    (body.allFieldNames() intersect forbiddenKeys).shouldBeEmpty()
                }
            }

            `when`("food_idol_video 스키마를 보면") {
                then("컬럼이 데이터 모델 10개와 정확히 같다") {
                    val columns = dataSource.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery(
                                "SELECT column_name FROM information_schema.columns " +
                                    "WHERE table_schema = DATABASE() AND table_name = 'food_idol_video'",
                            ).use { r -> buildSet { while (r.next()) add(r.getString(1)) } }
                        }
                    }
                    columns shouldBe setOf(
                        "id", "food_id", "platform", "video_url", "published_month", "hype", "position", "status", "created_at", "updated_at",
                    )
                }
            }

            `when`("매핑이 있는 상태에서 홈과 상세를 조회하면") {
                then("홈 1.3 에 idolPickFoods, 상세에 idolVideo 키가 없다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    video(1L, 4, "2026-01-01")

                    call("/api/home?lang=en", version = "1.3").second.path("payload").has("idolPickFoods") shouldBe false
                    val (status, detail) = call("/api/foods/1?lang=en")
                    status shouldBe 200
                    detail.path("payload").has("idolVideo") shouldBe false
                }
            }
        }
    }
}
