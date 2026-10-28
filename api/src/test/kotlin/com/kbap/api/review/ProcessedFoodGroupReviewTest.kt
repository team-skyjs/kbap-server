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
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodGroupReviewTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var members: MemberJpaRepository
    @Autowired private lateinit var writeSwitch: ProcessedFoodReviewSwitch
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory

    private val mapper = jacksonObjectMapper()

    init {
        fun sql(statement: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(statement, java.sql.Statement.RETURN_GENERATED_KEYS)
                st.generatedKeys.use { rs -> if (rs.next()) rs.getLong(1) else 0L }
            }
        }
        var seq = 0
        fun product(name: String, maker: String = "농심", seenDaysAgo: Int = 0): Long = sql(
            "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                "VALUES ('GR${System.nanoTime() % 1_000_000_000}${seq++}', '$name', '$name', '과자', '$maker', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6) - INTERVAL $seenDaysAgo DAY, 'ACTIVE', NOW(6), NOW(6))",
        )
        fun member(tag: String): Member = members.save(Member.signUp(SocialIdentity(SocialProvider.GOOGLE, "$tag-${System.nanoTime()}", null)))
        fun json(body: String): JsonNode = mapper.readTree(body)
        fun write(productId: Long, rating: Int, spiciness: Int? = null): Long = json(
            mvc.post("/api/processed-foods/$productId/reviews") {
                header("X-API-Version", "3.2")
                header("Authorization", "Bearer ${tokens.issueAccessToken(member("gr").id, MemberRole.USER)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("rating" to rating, "content" to "바삭하고 짭짤해서 맛있어요", "spiciness" to spiciness))
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("reviewId").asLong()
        fun get(path: String): JsonNode = json(
            mvc.get(path) { header("X-API-Version", "3.2") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")
        fun search(name: String): List<JsonNode> = get("/api/processed-foods?query=$name").path("items").toList()

        beforeEach {
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'GR%'")
            writeSwitch.writeEnabled = true
        }
        afterSpec {
            writeSwitch.writeEnabled = false
            TestTables.clearAll(dataSource)
            sql("DELETE FROM processed_food_product WHERE report_no LIKE 'GR%'")
        }

        given("같은 제품 묶음(정규화 이름+제조사)의 리뷰(KB-860)") {
            `when`("대표가 아닌 행에 리뷰가 달려 있으면") {
                then("대표 행 상세 요약·축이 묶음 전체로 집계되고 묶인 행 상세도 같다") {
                    val older = product("묶음과자", seenDaysAgo = 3)
                    val representative = product("묶음과자")
                    write(older, rating = 5, spiciness = 4)
                    write(representative, rating = 3)

                    listOf(representative, older).forEach { id ->
                        val summary = get("/api/processed-foods/$id").path("reviewSummary")
                        summary.path("count").asLong() shouldBe 2
                        summary.path("avgRating").asDouble() shouldBe 4.0
                        summary.path("taste").path("count").asLong() shouldBe 2
                        summary.path("spiciness").path("average").asDouble() shouldBe 4.0
                        summary.path("spiciness").path("count").asLong() shouldBe 1
                    }
                }
                then("검색 결과 카드의 평점도 묶음 전체 기준이다") {
                    val older = product("카드과자", seenDaysAgo = 3)
                    val representative = product("카드과자")
                    write(older, rating = 5)
                    write(older, rating = 4)
                    write(representative, rating = 3)

                    val items = search("카드과자")
                    items.map { it.path("id").asLong() } shouldBe listOf(representative)
                    items[0].path("reviewCount").asLong() shouldBe 3
                    items[0].path("avgRating").asDouble() shouldBe 4.0
                    items[0].path("rating").path("count").asLong() shouldBe 3
                }
                then("대표 행 리뷰 목록에 묶음 전체 리뷰가 최신순으로 보이고 커서가 묶음 전체에서 이어진다") {
                    val older = product("목록묶음과자", seenDaysAgo = 3)
                    val representative = product("목록묶음과자")
                    val ids = listOf(write(older, 5), write(representative, 4), write(older, 3))

                    get("/api/processed-foods/$representative/reviews?lang=en").path("items").map { it.path("reviewId").asLong() } shouldBe ids.reversed()
                    get("/api/processed-foods/$representative/reviews?lang=en&cursor=${ids[2]}").path("items").map { it.path("reviewId").asLong() } shouldBe ids.take(2).reversed()
                }
            }
            `when`("목록 24건이 모두 묶음이면") {
                then("묶음 멤버는 색인에서 한 번에 읽어 1건 목록과 쿼리 수가 같다") {
                    (1..24).forEach { n ->
                        val older = product("쿼리과자$n", seenDaysAgo = 3)
                        product("쿼리과자$n")
                        if (n <= 3) write(older, rating = 4)
                    }
                    get("/api/processed-foods?size=24")
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    val originallyEnabled = statistics.isStatisticsEnabled
                    statistics.isStatisticsEnabled = true
                    try {
                        val counts = listOf(1, 24).map { size ->
                            statistics.clear()
                            get("/api/processed-foods?size=$size").path("items").size() shouldBe size
                            statistics.prepareStatementCount
                        }
                        counts.last() shouldBe counts.first()
                    } finally {
                        statistics.isStatisticsEnabled = originallyEnabled
                    }
                }
            }
            `when`("이름이 같아도 제조사가 다르면") {
                then("다른 묶음이라 리뷰가 섞이지 않는다") {
                    val other = product("갈래과자", maker = "오리온")
                    val mine = product("갈래과자")
                    write(other, rating = 1)
                    write(mine, rating = 5)

                    get("/api/processed-foods/$mine").path("reviewSummary").path("count").asLong() shouldBe 1
                    get("/api/processed-foods/$mine/reviews?lang=en").path("items").size() shouldBe 1
                    search("갈래과자").associate { it.path("id").asLong() to it.path("reviewCount").asLong() } shouldBe mapOf(other to 1L, mine to 1L)
                }
            }
        }
    }
}
