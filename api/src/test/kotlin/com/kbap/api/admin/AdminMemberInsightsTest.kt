package com.kbap.api.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.review.model.ReviewTargetType
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.sql.DataSource

@IntegrationTest
class AdminMemberInsightsTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var memberJpaRepository: MemberJpaRepository
    @Autowired private lateinit var foodJpaRepository: FoodJpaRepository
    @Autowired private lateinit var reviewJpaRepository: ReviewJpaRepository
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var dataSource: DataSource

    init {
        val seoul = ZoneId.of("Asia/Seoul")
        val mapper = jacksonObjectMapper()

        fun getJson(target: String, role: MemberRole = MemberRole.ADMIN): ResultActionsDsl =
            mockMvc.get(target) { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, role)}") }

        fun payload(target: String): JsonNode =
            mapper.readTree(getJson(target).andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun saveMember(uid: String, country: String? = null, bot: Boolean = false, nickname: String? = uid): Member {
            val member = memberJpaRepository.save(Member(provider = SocialProvider.GOOGLE, providerUid = uid, email = "$uid@test.com", nickname = nickname))
            execute("UPDATE member SET country_code = ${country?.let { "'$it'" } ?: "NULL"}, is_bot = ${if (bot) 1 else 0} WHERE id = ${member.id}")
            return member
        }

        fun createdAtKst(memberId: Long, date: LocalDate, time: LocalTime) {
            val local = date.atTime(time).atZone(seoul).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            execute("UPDATE member SET created_at = '$local' WHERE id = $memberId")
        }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("어드민 회원 목록 국적 필터 — GET /api/admin/members?country=") {
            `when`("국적이 섞인 회원을 국적·NONE·봇·검색어로 조합해 거르면") {
                then("항목에 countryCode 가 오고, 필터는 ISO2 정확 일치·NONE=미설정·q·isBot 과 AND 로 걸린다") {
                    val kr = saveMember("kr-human", country = "KR", nickname = "김밥러버")
                    val krBot = saveMember("kr-bot", country = "KR", bot = true, nickname = "김밥봇")
                    val us = saveMember("us-human", country = "US", nickname = "버거러버")
                    val none = saveMember("no-country", country = null, nickname = "무국적")

                    getJson("/api/admin/members").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].id") { value(none.id) }
                        jsonPath("$.payload.items[0].countryCode") { doesNotExist() }
                        jsonPath("$.payload.items[1].countryCode") { value("US") }
                    }
                    payload("/api/admin/members?country=KR").let { p ->
                        p.path("totalCount").asLong() shouldBe 2L
                        p.path("items").map { it.path("id").asLong() } shouldBe listOf(krBot.id, kr.id)
                    }
                    getJson("/api/admin/members?country=kr&isBot=false").andExpect {
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(kr.id) }
                    }
                    getJson("/api/admin/members?country=KR&q=김밥&isBot=true").andExpect {
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(krBot.id) }
                    }
                    getJson("/api/admin/members?country=NONE").andExpect {
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(none.id) }
                    }
                    getJson("/api/admin/members?country=US&q=김밥").andExpect { jsonPath("$.payload.totalCount") { value(0) } }
                    getJson("/api/admin/members?country=KOR").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                    us.id
                }
            }
        }

        given("어드민 국적별 회원 수 — GET /api/admin/members/country-counts") {
            `when`("국적·봇·탈퇴가 섞여 있으면") {
                then("모든 상태를 세고, 미설정은 null 한 행, 건수 내림차순이며 isBot 으로 거른다") {
                    saveMember("a", "KR"); saveMember("b", "KR"); saveMember("c", "KR", bot = true)
                    saveMember("d", "US"); saveMember("e", null); saveMember("f", null)
                    val withdrawn = saveMember("g", "JP")
                    execute("UPDATE member SET member_status = 'SUSPENDED', status = 'DELETED' WHERE id = ${withdrawn.id}")

                    getJson("/api/admin/members/country-counts").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.length()") { value(4) }
                        jsonPath("$.payload[0].countryCode") { value("KR") }
                        jsonPath("$.payload[0].count") { value(3) }
                        jsonPath("$.payload[1].countryCode") { doesNotExist() }
                        jsonPath("$.payload[1].count") { value(2) }
                    }
                    payload("/api/admin/members/country-counts").first { it.path("countryCode").asText() == "JP" }.path("count").asLong() shouldBe 1L
                    getJson("/api/admin/members/country-counts?isBot=false").andExpect {
                        jsonPath("$.payload[0].countryCode") { value("KR") }
                        jsonPath("$.payload[0].count") { value(2) }
                    }
                    getJson("/api/admin/members/country-counts?isBot=true").andExpect {
                        jsonPath("$.payload.length()") { value(1) }
                        jsonPath("$.payload[0].count") { value(1) }
                    }
                }
            }
        }

        given("어드민 일자별 가입 수 — GET /api/admin/dashboard/signups") {
            `when`("최근 며칠 가입이 KST 자정 경계 양쪽에 걸쳐 있으면") {
                then("KST 날짜로 세고, 빈 날은 0, 오래된 순으로 days 개, 봇 필터가 걸린다") {
                    val today = LocalDate.now(seoul)
                    createdAtKst(saveMember("late").id, today.minusDays(1), LocalTime.of(23, 59, 59))
                    createdAtKst(saveMember("early").id, today, LocalTime.of(0, 0, 0))
                    createdAtKst(saveMember("bot", bot = true).id, today, LocalTime.of(0, 0, 1))
                    createdAtKst(saveMember("old").id, today.minusDays(5), LocalTime.NOON)
                    createdAtKst(saveMember("too-old").id, today.minusDays(7), LocalTime.NOON)

                    getJson("/api/admin/dashboard/signups?days=7").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.length()") { value(7) }
                        jsonPath("$.payload[0].date") { value(today.minusDays(6).toString()) }
                        jsonPath("$.payload[1].count") { value(1) }
                        jsonPath("$.payload[2].count") { value(0) }
                        jsonPath("$.payload[5].date") { value(today.minusDays(1).toString()) }
                        jsonPath("$.payload[5].count") { value(1) }
                        jsonPath("$.payload[6].date") { value(today.toString()) }
                        jsonPath("$.payload[6].count") { value(2) }
                    }
                    getJson("/api/admin/dashboard/signups?days=7&isBot=false").andExpect { jsonPath("$.payload[6].count") { value(1) } }
                    getJson("/api/admin/dashboard/signups").andExpect { jsonPath("$.payload.length()") { value(30) } }
                    listOf(0, 91).forEach { days ->
                        getJson("/api/admin/dashboard/signups?days=$days").andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMON-002") }
                        }
                    }
                }
            }
        }

        given("어드민 전체 리뷰 — GET /api/admin/reviews") {
            `when`("사람·봇이 음식·가공식품에 쓴 리뷰가 있으면") {
                then("회원 리뷰 페이지와 같은 봉투로 id 내림차순, 작성자 닉네임·봇 여부·대상 이름을 주고 isBot 으로 거른다") {
                    val human = saveMember("human", nickname = "사람")
                    val bot = saveMember("reviewbot", bot = true, nickname = "봇")
                    val food = foodJpaRepository.save(Food(koreanName = "김치찌개", description = "구수한 김치찌개"))
                    execute(
                        "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                            "VALUES ('ADM-R-1', '새우깡', '새우깡', '과자', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
                    )
                    val productId = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT id FROM processed_food_product WHERE report_no = 'ADM-R-1'").use { it.next(); it.getLong(1) } } }
                    val foodReview = reviewJpaRepository.save(Review(memberId = human.id, foodId = food.id, rating = 5, servingSpeedRating = 4, staffKindnessRating = 3, content = "정말 맛있게 먹었어요"))
                    val productReview = reviewJpaRepository.save(Review(memberId = human.id, foodId = null, rating = 4, targetType = ReviewTargetType.PROCESSED_FOOD, processedFoodId = productId))
                    val botReview = reviewJpaRepository.save(Review(memberId = bot.id, foodId = food.id, rating = 3))

                    getJson("/api/admin/reviews").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalCount") { value(3) }
                        jsonPath("$.payload.page") { value(1) }
                        jsonPath("$.payload.totalPages") { value(1) }
                        jsonPath("$.payload.hasPrev") { value(false) }
                        jsonPath("$.payload.hasNext") { value(false) }
                        jsonPath("$.payload.items[0].isBot") { value(true) }
                        jsonPath("$.payload.items[0].memberNickname") { value("봇") }
                        jsonPath("$.payload.items[1].targetType") { value("PROCESSED_FOOD") }
                        jsonPath("$.payload.items[1].processedFoodId") { value(productId) }
                        jsonPath("$.payload.items[1].targetName") { value("새우깡") }
                        jsonPath("$.payload.items[2].targetType") { value("FOOD") }
                        jsonPath("$.payload.items[2].foodId") { value(food.id) }
                        jsonPath("$.payload.items[2].targetName") { value("김치찌개") }
                        jsonPath("$.payload.items[2].memberId") { value(human.id) }
                        jsonPath("$.payload.items[2].servingSpeedRating") { value(4) }
                        jsonPath("$.payload.items[2].staffKindnessRating") { value(3) }
                        jsonPath("$.payload.items[2].content") { value("정말 맛있게 먹었어요") }
                        jsonPath("$.payload.items[2].imageUrls.length()") { value(0) }
                        jsonPath("$.payload.items[2].createdAt") { exists() }
                    }
                    payload("/api/admin/reviews").path("items").map { it.path("id").asLong() } shouldBe listOf(botReview.id, productReview.id, foodReview.id)
                    payload("/api/admin/reviews?isBot=false").let { p ->
                        p.path("totalCount").asLong() shouldBe 2L
                        p.path("items").map { it.path("isBot").asBoolean() } shouldBe listOf(false, false)
                    }
                    getJson("/api/admin/reviews?isBot=true").andExpect { jsonPath("$.payload.totalCount") { value(1) } }
                }
            }
            `when`("21개면") {
                then("20개씩 나뉘고 2쪽에 1개") {
                    val member = saveMember("many")
                    val food = foodJpaRepository.save(Food(koreanName = "비빔밥", description = "고소한 비빔밥"))
                    repeat(21) { reviewJpaRepository.save(Review(memberId = member.id, foodId = food.id, rating = 5)) }
                    getJson("/api/admin/reviews").andExpect {
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.totalPages") { value(2) }
                        jsonPath("$.payload.hasNext") { value(true) }
                    }
                    getJson("/api/admin/reviews?page=2").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.hasPrev") { value(true) }
                    }
                }
            }
        }

        given("관리자가 아닌 토큰") {
            `when`("새 어드민 API 들을 부르면") {
                then("403 AUTH-008") {
                    listOf("/api/admin/members?country=KR", "/api/admin/members/country-counts", "/api/admin/dashboard/signups", "/api/admin/reviews").forEach { target ->
                        getJson(target, MemberRole.USER).andExpect {
                            status { isForbidden() }
                            jsonPath("$.code") { value("AUTH-008") }
                        }
                    }
                }
            }
        }
    }
}
