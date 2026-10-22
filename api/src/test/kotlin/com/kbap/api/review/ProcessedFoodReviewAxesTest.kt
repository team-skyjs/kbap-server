package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodReviewAxesTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var writeSwitch: ProcessedFoodReviewSwitch

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                if (statement.trimStart().startsWith("SELECT")) st.executeQuery(statement).use { rs -> if (rs.next()) rs.getLong(1) else -1L }
                else { st.executeUpdate(statement, java.sql.Statement.RETURN_GENERATED_KEYS); st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
            }
        }
        fun nullable(statement: String): Int? = dataSource.connection.use { c ->
            c.createStatement().use { st -> st.executeQuery(statement).use { rs -> rs.next(); rs.getInt(1).takeUnless { rs.wasNull() } } }
        }
        var seq = 0
        fun product(name: String): Long = sql(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES ('AX${System.nanoTime() % 1_000_000_000}${seq++}', '$name', '$name', '과자', '농심', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
        )
        fun member(tag: String): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "$tag-${System.nanoTime()}", null)))
        fun token(m: Member) = tokens.issueAccessToken(m.id, MemberRole.USER)
        fun json(body: String): JsonNode = mapper.readTree(body)
        fun write(productId: Long, author: Member, body: Map<String, Any?>, expected: Int = 200): JsonNode = json(
            mvc.post("/api/processed-foods/$productId/reviews") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${token(author)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("content" to "바삭하고 짭짤해서 맛있어요") + body)
            }.andExpect { status { isEqualTo(expected) } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun update(reviewId: Long, author: Member, body: Map<String, Any?>): JsonNode = json(
            mvc.patch("/api/reviews/$reviewId") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${token(author)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("content" to "다시 먹어보니 더 맛있네요") + body)
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        fun detail(productId: Long): JsonNode = json(
            mvc.get("/api/processed-foods/$productId") { header("X-API-Version", "3.2") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

        beforeEach {
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'AX%'")
            writeSwitch.writeEnabled = true
        }
        afterSpec {
            writeSwitch.writeEnabled = false
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'AX%'")
        }

        given("가공식품 리뷰 맵기·당도(KB-869)") {
            `when`("맵기·당도를 함께 쓰면") {
                then("1~5 정수로 저장되고 응답에 실린다") {
                    val review = write(product("매운과자"), member("ax-1"), mapOf("rating" to 4, "spiciness" to 4, "sweetness" to 2)).path("payload")
                    review.path("spiciness").asInt() shouldBe 4
                    review.path("sweetness").asInt() shouldBe 2
                    val id = review.path("reviewId").asLong()
                    nullable("SELECT spiciness_rating FROM food_review WHERE id = $id") shouldBe 4
                    nullable("SELECT sweetness_rating FROM food_review WHERE id = $id") shouldBe 2
                }
            }
            `when`("맵기·당도를 비우면") {
                then("0 이 아니라 NULL 로 저장되고 응답도 null 이다") {
                    val review = write(product("순한과자"), member("ax-2"), mapOf("rating" to 4)).path("payload")
                    review.path("spiciness").isNull shouldBe true
                    val id = review.path("reviewId").asLong()
                    nullable("SELECT spiciness_rating FROM food_review WHERE id = $id") shouldBe null
                    nullable("SELECT sweetness_rating FROM food_review WHERE id = $id") shouldBe null
                }
            }
            `when`("범위를 벗어나면") {
                then("요청 검증에서 400 이다") {
                    val productId = product("범위과자")
                    write(productId, member("ax-3"), mapOf("rating" to 4, "spiciness" to 0), expected = 400).path("message").asText().startsWith("spiciness:") shouldBe true
                    write(productId, member("ax-4"), mapOf("rating" to 4, "sweetness" to 6), expected = 400).path("message").asText().startsWith("sweetness:") shouldBe true
                }
            }
            `when`("상세 리뷰 요약을 보면") {
                then("축마다 {average, count} 이고 미입력·범위 밖 값은 분모에서 빠지며 overall 은 있는 축의 평균이다") {
                    val productId = product("집계과자")
                    write(productId, member("ax-5"), mapOf("rating" to 5, "spiciness" to 4))
                    write(productId, member("ax-6"), mapOf("rating" to 3, "spiciness" to 2, "sweetness" to 2))
                    val outside = write(productId, member("ax-7"), mapOf("rating" to 4)).path("payload").path("reviewId").asLong()
                    sql("UPDATE food_review SET spiciness_rating = 9, sweetness_rating = 0 WHERE id = $outside")

                    val summary = detail(productId).path("reviewSummary")
                    summary.path("taste").path("average").asDouble() shouldBe 4.0
                    summary.path("taste").path("count").asLong() shouldBe 3L
                    summary.path("spiciness").path("average").asDouble() shouldBe 3.0
                    summary.path("spiciness").path("count").asLong() shouldBe 2L
                    summary.path("sweetness").path("average").asDouble() shouldBe 2.0
                    summary.path("sweetness").path("count").asLong() shouldBe 1L
                    summary.path("overall").path("average").asDouble() shouldBe 3.0
                    summary.path("overall").path("count").asLong() shouldBe 3L
                    summary.path("count").asLong() shouldBe 3L
                }
                then("리뷰가 없으면 축마다 average null·count 0 이다") {
                    val summary = detail(product("무리뷰과자")).path("reviewSummary")
                    listOf("overall", "taste", "spiciness", "sweetness").forEach { axis ->
                        summary.path(axis).path("average").isNull shouldBe true
                        summary.path(axis).path("count").asLong() shouldBe 0L
                    }
                }
            }
            `when`("리뷰를 수정하면") {
                then("맵기·당도를 바꿀 수 있고 생략하면 비운다(NULL)") {
                    val author = member("ax-8")
                    val id = write(product("수정과자"), author, mapOf("rating" to 4, "spiciness" to 1, "sweetness" to 1)).path("payload").path("reviewId").asLong()

                    update(id, author, mapOf("rating" to 4, "spiciness" to 5)).path("payload").path("spiciness").asInt() shouldBe 5
                    nullable("SELECT spiciness_rating FROM food_review WHERE id = $id") shouldBe 5
                    nullable("SELECT sweetness_rating FROM food_review WHERE id = $id") shouldBe null
                }
            }
        }
    }
}
