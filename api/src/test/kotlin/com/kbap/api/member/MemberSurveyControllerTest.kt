package com.kbap.api.member

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@IntegrationTest
class MemberSurveyControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun loginAccessToken(sub: String = FakeSocialTokenVerifier.DEFAULT_SUB): String {
            val response = mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun putSurvey(token: String?, body: Map<String, Any?>) =
            mockMvc.put("/api/members/me/survey") {
                if (token != null) header("Authorization", "Bearer $token")
                header("X-API-Version", "1.0")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun getMyProfile(token: String) =
            mockMvc.get("/api/members/me/profile") {
                header("Authorization", "Bearer $token")
                header("X-API-Version", "1.0")
            }

        fun withdraw(token: String) =
            mockMvc.patch("/api/auth/withdraw") {
                header("Authorization", "Bearer $token")
                header("X-API-Version", "1.0")
            }

        fun payload(response: org.springframework.mock.web.MockHttpServletResponse): JsonNode =
            objectMapper.readTree(response.contentAsString).path("payload")

        fun code(response: org.springframework.mock.web.MockHttpServletResponse): String =
            objectMapper.readTree(response.contentAsString).path("code").asText()

        fun validBody() = mapOf(
            "ageBand" to "TWENTIES",
            "gender" to "FEMALE",
            "acquisition" to "SNS_AD",
            "situation" to "TRIP_PLANNED",
            "tripTiming" to "THIS_YEAR",
            "tripDuration" to "ONE_WEEK",
            "purpose" to "MENU_READING",
            "foodAffinity" to 4,
        )

        fun surveyRows(): List<Map<String, Any?>> =
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT member_id, situation, trip_timing, trip_duration, food_affinity, survey_version FROM member_survey").use { rs ->
                        generateSequence { if (rs.next()) mapOf(
                            "memberId" to rs.getLong("member_id"),
                            "situation" to rs.getString("situation"),
                            "tripTiming" to rs.getString("trip_timing"),
                            "tripDuration" to rs.getString("trip_duration"),
                            "foodAffinity" to rs.getInt("food_affinity"),
                            "surveyVersion" to rs.getInt("survey_version"),
                        ) else null }.toList()
                    }
                }
            }

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("프로필 설문 제출 PUT /api/members/me/survey") {
            `when`("회원이 유효한 응답을 보내면") {
                then("저장하고 본문 + answeredAt 을 돌려준다") {
                    val token = loginAccessToken()
                    val response = putSurvey(token, validBody()).andReturn().response

                    response.status shouldBe 200
                    val body = payload(response)
                    body.path("situation").asText() shouldBe "TRIP_PLANNED"
                    body.path("tripTiming").asText() shouldBe "THIS_YEAR"
                    body.path("tripDuration").asText() shouldBe "ONE_WEEK"
                    body.path("foodAffinity").asInt() shouldBe 4
                    body.path("surveyVersion").asInt() shouldBe 1
                    body.path("answeredAt").isTextual shouldBe true
                    surveyRows().single()["foodAffinity"] shouldBe 4
                }
            }

            `when`("같은 회원이 다시 보내면") {
                then("행을 늘리지 않고 같은 행을 덮어쓴다(멱등 upsert) — answeredAt 은 뒤로 간다") {
                    val token = loginAccessToken()
                    val first = payload(putSurvey(token, validBody()).andReturn().response).path("answeredAt").asText()

                    val second = putSurvey(token, validBody() + mapOf("situation" to "LIVING_IN_KOREA", "tripTiming" to null, "tripDuration" to null, "foodAffinity" to 2))
                        .andReturn().response

                    second.status shouldBe 200
                    payload(second).path("situation").asText() shouldBe "LIVING_IN_KOREA"
                    payload(second).path("tripTiming").isNull shouldBe true
                    val rows = surveyRows()
                    rows.size shouldBe 1
                    rows.single()["situation"] shouldBe "LIVING_IN_KOREA"
                    rows.single()["tripDuration"] shouldBe null
                    rows.single()["foodAffinity"] shouldBe 2
                    LocalDateTime.parse(payload(second).path("answeredAt").asText()) shouldBeGreaterThanOrEqualTo LocalDateTime.parse(first)
                }
            }

            `when`("여행 예정인데 여행 시기를 비우면") {
                then("400 COMMON-002 로 거절하고 저장하지 않는다") {
                    val token = loginAccessToken()
                    val response = putSurvey(token, validBody() + mapOf("tripTiming" to null)).andReturn().response

                    response.status shouldBe 400
                    code(response) shouldBe "COMMON-002"
                    surveyRows() shouldBe emptyList()
                }
            }

            `when`("거주 중인데 여행 시기·기간이 남아서 오면") {
                then("거절하지 않고 null 로 정규화해 저장한다(상황을 바꾼 뒤 남은 값에 갇히지 않게)") {
                    val token = loginAccessToken()
                    val response = putSurvey(token, validBody() + mapOf("situation" to "LIVING_IN_KOREA")).andReturn().response

                    response.status shouldBe 200
                    payload(response).path("tripTiming").isNull shouldBe true
                    payload(response).path("tripDuration").isNull shouldBe true
                    surveyRows().single()["tripTiming"] shouldBe null
                    surveyRows().single()["tripDuration"] shouldBe null
                }
            }

            `when`("여행 중(TRAVELING_NOW)인데 여행 시기가 남아서 오면") {
                then("기간은 저장하고 시기만 null 로 정규화한다") {
                    val token = loginAccessToken()
                    val response = putSurvey(token, validBody() + mapOf("situation" to "TRAVELING_NOW")).andReturn().response

                    response.status shouldBe 200
                    payload(response).path("tripTiming").isNull shouldBe true
                    payload(response).path("tripDuration").asText() shouldBe "ONE_WEEK"
                }
            }

            `when`("탈퇴한 회원이 남은 토큰으로 보내면") {
                then("400 MEMBER-003 으로 거절하고 행을 만들지 않는다") {
                    val token = loginAccessToken()
                    withdraw(token).andReturn().response.status shouldBe 200

                    val response = putSurvey(token, validBody()).andReturn().response

                    response.status shouldBe 400
                    code(response) shouldBe "MEMBER-003"
                    surveyRows() shouldBe emptyList()
                }
            }

            `when`("같은 회원이 첫 제출을 동시에 두 번 보내면") {
                then("둘 다 200 이고 행은 1개다(회원 행 잠금으로 직렬화)") {
                    val token = loginAccessToken()
                    val start = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    val statuses = (1..2).map { attempt ->
                        pool.submit(Callable {
                            start.await()
                            putSurvey(token, validBody() + mapOf("foodAffinity" to attempt)).andReturn().response.status
                        })
                    }
                    start.countDown()
                    val results = statuses.map { it.get(30, TimeUnit.SECONDS) }
                    pool.shutdown()

                    results shouldBe listOf(200, 200)
                    surveyRows().size shouldBe 1
                }
            }

            `when`("선택지에 없는 코드나 범위 밖 점수를 보내면") {
                then("400 COMMON-002") {
                    val token = loginAccessToken()
                    code(putSurvey(token, validBody() + mapOf("gender" to "UNKNOWN")).andReturn().response) shouldBe "COMMON-002"
                    code(putSurvey(token, validBody() + mapOf("foodAffinity" to 6)).andReturn().response) shouldBe "COMMON-002"
                    code(putSurvey(token, validBody() - "ageBand").andReturn().response) shouldBe "COMMON-002"
                    surveyRows() shouldBe emptyList()
                }
            }

            `when`("게스트(토큰 없음)가 보내면") {
                then("401") {
                    putSurvey(null, validBody()).andReturn().response.status shouldBe 401
                }
            }
        }

        given("내 프로필의 surveyCompleted") {
            `when`("설문 제출 전후로 조회하면") {
                then("false 였다가 true 가 된다") {
                    val token = loginAccessToken()
                    payload(getMyProfile(token).andReturn().response).path("surveyCompleted").asBoolean() shouldBe false

                    putSurvey(token, validBody()).andReturn().response.status shouldBe 200

                    payload(getMyProfile(token).andReturn().response).path("surveyCompleted").asBoolean() shouldBe true
                }
            }

            `when`("옛 문항 버전으로 답한 행만 있으면") {
                then("false 다 — 다시 제출하면 현재 버전으로 덮어써 true 가 된다") {
                    val token = loginAccessToken()
                    insertSurveyRow(memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB), surveyVersion = 0)
                    payload(getMyProfile(token).andReturn().response).path("surveyCompleted").asBoolean() shouldBe false

                    putSurvey(token, validBody()).andReturn().response.status shouldBe 200

                    payload(getMyProfile(token).andReturn().response).path("surveyCompleted").asBoolean() shouldBe true
                    surveyRows().single()["surveyVersion"] shouldBe 1
                }
            }
        }

        given("탈퇴 파기") {
            `when`("설문을 낸 회원이 탈퇴하면") {
                then("member_survey 행이 남지 않는다 — 다른 회원의 행은 그대로다") {
                    val mine = loginAccessToken()
                    val other = loginAccessToken("survey-other-member")
                    putSurvey(mine, validBody()).andReturn().response.status shouldBe 200
                    putSurvey(other, validBody()).andReturn().response.status shouldBe 200
                    surveyRows().size shouldBe 2

                    withdraw(mine).andReturn().response.status shouldBe 200

                    val rows = surveyRows()
                    rows.size shouldBe 1
                    rows.single()["memberId"] shouldBe memberIdOf("survey-other-member")
                }
            }
        }
    }

    private fun insertSurveyRow(memberId: Long, surveyVersion: Int) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO member_survey (member_id, age_band, gender, acquisition, situation, purpose, food_affinity, survey_version, answered_at, status, created_at, updated_at) " +
                    "VALUES (?, 'TWENTIES', 'FEMALE', 'SNS_AD', 'LIVING_IN_KOREA', 'MENU_READING', 3, ?, NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            ).use { ps ->
                ps.setLong(1, memberId)
                ps.setInt(2, surveyVersion)
                ps.executeUpdate()
            }
        }
    }

    private fun memberIdOf(providerUid: String): Long =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                ps.setString(1, providerUid)
                ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
            }
        }
}
