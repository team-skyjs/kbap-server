package com.kbap.app.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.app.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class MemberControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun clearMembers() {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member") } }
        }

        fun loginAccessToken(): String {
            val response = mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to "valid-token"))
            }.andReturn().response
            return objectMapper.readTree(response.contentAsString).path("payload").path("accessToken").asText()
        }

        fun submitOnboarding(token: String?, body: Map<String, Any?>) =
            mockMvc.post("/api/v1/members/me/onboarding") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun getMyProfile(token: String?) =
            mockMvc.get("/api/v1/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun updateProfile(token: String?, body: Map<String, Any?>) =
            mockMvc.patch("/api/v1/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun validBody() = mapOf(
            "nickname" to "길동이",
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "appLanguage" to "en",
        )

        fun memberColumn(providerUid: String, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun getMyRanking(token: String?) =
            mockMvc.get("/api/v1/members/me/ranking") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun seedCounts(scanCount: Int = 0, reviewCount: Int = 0, uniqueReviewedFoodCount: Int = 0) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE member SET scan_count = ?, review_count = ?, unique_reviewed_food_count = ? " +
                        "WHERE provider_uid = ?",
                ).use { ps ->
                    ps.setInt(1, scanCount)
                    ps.setInt(2, reviewCount)
                    ps.setInt(3, uniqueReviewedFoodCount)
                    ps.setString(4, "google-sub-fixed")
                    ps.executeUpdate()
                }
            }
        }

        fun seedScanCount(scanCount: Int) = seedCounts(scanCount = scanCount)

        beforeContainer {
            clearMembers()
        }

        given("온보딩 미완료 회원") {
            `when`("유효한 온보딩 정보를 제출하면") {
                then("200 으로 응답하고 프로필·온보딩 완료가 저장된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    memberColumn("google-sub-fixed", "nickname") shouldBe "길동이"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }

            `when`("이미 온보딩을 완료한 뒤 다시 제출하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "\"success\":false"
                    result.contentAsString shouldContain "이미 온보딩을 완료했습니다"
                }
            }
        }

        given("무효 입력 온보딩 제출 — 저장 없이 400") {
            listOf(
                "카탈로그에 없는 기피 성분" to validBody() + ("avoidanceSubstanceCodes" to listOf("NOT_A_CODE")),
                "지정 목록에 없는 국가" to validBody() + ("countryCode" to "ZZ"),
                "지원하지 않는 언어" to validBody() + ("appLanguage" to "fr"),
                "빈 닉네임" to validBody() + ("nickname" to "   "),
            ).forEach { (label, body) ->
                `when`(label + "을 제출하면") {
                    then("400 으로 거절되고 프로필·온보딩 상태가 변하지 않는다") {
                        val token = loginAccessToken()

                        val result = submitOnboarding(token, body).andReturn().response

                        result.status shouldBe 400
                        result.contentAsString shouldContain "\"success\":false"
                        memberColumn("google-sub-fixed", "nickname") shouldBe null
                        memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    }
                }
            }

            `when`("무효 입력으로 거절된 뒤 유효 입력으로 다시 제출하면") {
                then("200 으로 정상 처리된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody() + ("countryCode" to "ZZ")).andReturn()

                    val result = submitOnboarding(token, validBody()).andReturn().response

                    result.status shouldBe 200
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }
        }

        given("인증 토큰 없는 온보딩 제출") {
            `when`("Authorization 헤더 없이 제출하면") {
                then("401 로 거절된다") {
                    submitOnboarding(null, validBody()).andReturn().response.status shouldBe 401
                }
            }

            `when`("위조된 토큰으로 제출하면") {
                then("401 로 거절된다") {
                    submitOnboarding("forged.access.token", validBody()).andReturn().response.status shouldBe 401
                }
            }
        }

        given("내 프로필 조회") {
            `when`("온보딩을 완료한 회원이 조회하면") {
                then("저장된 프로필과 온보딩 완료 상태가 응답에 담긴다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("countryCode").asText() shouldBe "US"
                    payload.path("appLanguage").asText() shouldBe "en"
                    payload.path("onboardingCompleted").asBoolean() shouldBe true
                }
            }

            `when`("온보딩 미완료 회원이 조회하면") {
                then("온보딩 미완료 상태로 응답한다") {
                    val token = loginAccessToken()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("onboardingCompleted").asBoolean() shouldBe false
                    payload.path("nickname").isNull shouldBe true
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    getMyProfile(null).andReturn().response.status shouldBe 401
                }
            }
        }

        given("프로필 수정") {
            fun updateBody() = mapOf(
                "nickname" to "수정닉",
                "avoidanceSubstanceCodes" to listOf("PEANUT"),
                "countryCode" to "JP",
                "appLanguage" to "ja",
            )

            `when`("온보딩을 완료한 회원이 유효한 값으로 수정하면") {
                then("200 으로 응답하고 프로필이 갱신되며 온보딩 상태는 유지된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = updateProfile(token, updateBody()).andReturn().response

                    result.status shouldBe 200
                    memberColumn("google-sub-fixed", "nickname") shouldBe "수정닉"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "1"
                }
            }

            `when`("무효한 값으로 수정하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()

                    val result = updateProfile(token, updateBody() + ("countryCode" to "ZZ")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "\"success\":false"
                }
            }

            `when`("인증 없이 수정하면") {
                then("401 로 거절된다") {
                    updateProfile(null, updateBody()).andReturn().response.status shouldBe 401
                }
            }
        }
        given("프로필 부분 수정 — 보내지 않은 필드는 유지된다") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            fun onboardedToken(): String {
                val token = loginAccessToken()
                submitOnboarding(token, validBody()).andReturn()
                return token
            }

            `when`("닉네임·국가·언어만 보내면") {
                then("기피 성분이 삭제되지 않고 그대로 유지된다") {
                    val token = onboardedToken()

                    val result = updateProfile(
                        token,
                        mapOf("nickname" to "새닉", "countryCode" to "JP", "appLanguage" to "ja"),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.path("countryCode").asText() shouldBe "JP"
                    payload.path("appLanguage").asText() shouldBe "ja"
                    payload.path("avoidanceSubstanceCodes").map { it.asText() }.toSet() shouldBe setOf("EGG", "MILK")
                }
            }

            `when`("기피 성분만 보내면") {
                then("기피 성분만 교체되고 닉네임·국가·언어는 유지된다") {
                    val token = onboardedToken()

                    val result = updateProfile(
                        token,
                        mapOf("avoidanceSubstanceCodes" to listOf("PEANUT")),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("avoidanceSubstanceCodes").map { it.asText() } shouldBe listOf("PEANUT")
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("countryCode").asText() shouldBe "US"
                    payload.path("appLanguage").asText() shouldBe "en"
                }
            }

            `when`("기피 성분에 빈 배열을 보내면") {
                then("기피 성분이 전부 해제된다") {
                    val token = onboardedToken()

                    updateProfile(token, mapOf("avoidanceSubstanceCodes" to emptyList<String>()))
                        .andReturn().response.status shouldBe 200

                    profilePayload(token).path("avoidanceSubstanceCodes").isEmpty shouldBe true
                }
            }

            `when`("빈 본문을 보내면") {
                then("200 으로 응답하고 프로필이 하나도 바뀌지 않는다") {
                    val token = onboardedToken()

                    updateProfile(token, emptyMap()).andReturn().response.status shouldBe 200

                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("countryCode").asText() shouldBe "US"
                    payload.path("appLanguage").asText() shouldBe "en"
                    payload.path("avoidanceSubstanceCodes").map { it.asText() }.toSet() shouldBe setOf("EGG", "MILK")
                }
            }

            `when`("전달한 국가 코드만 무효하면") {
                then("400 으로 거절되고 프로필은 하나도 바뀌지 않는다") {
                    val token = onboardedToken()

                    updateProfile(token, mapOf("countryCode" to "ZZ")).andReturn().response.status shouldBe 400

                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("countryCode").asText() shouldBe "US"
                }
            }

            `when`("유효한 닉네임만 보내면") {
                then("국가·언어를 보내지 않았다는 이유로 거절되지 않는다") {
                    val token = onboardedToken()

                    updateProfile(token, mapOf("nickname" to "새닉")).andReturn().response.status shouldBe 200

                    profilePayload(token).path("nickname").asText() shouldBe "새닉"
                }
            }
        }

        given("온보딩 입력 정규화") {
            `when`("닉네임 앞뒤 공백과 중복 성분 코드를 제출하면") {
                then("공백은 제거되고 성분은 중복 없이 저장된다") {
                    val token = loginAccessToken()

                    submitOnboarding(
                        token,
                        mapOf(
                            "nickname" to "  길동이  ",
                            "avoidanceSubstanceCodes" to listOf("EGG", "EGG", "MILK"),
                            "countryCode" to "US",
                            "appLanguage" to "en",
                        ),
                    ).andExpect { status { isOk() } }

                    val result = getMyProfile(token).andReturn().response
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("avoidanceSubstanceCodes").map { it.asText() }.toSet() shouldBe setOf("EGG", "MILK")
                    payload.path("avoidanceSubstanceCodes").size() shouldBe 2
                }
            }
        }

        given("프로필 부분 수정 — API 로 노출되지 않는 값") {
            `when`("닉네임만 수정하면") {
                then("맵기 선호도는 보존된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andExpect { status { isOk() } }

                    updateProfile(token, mapOf("nickname" to "새닉")).andExpect { status { isOk() } }

                    val profileJson = memberColumn("google-sub-fixed", "profile")!!
                    profileJson.contains("\"spicinessPreference\"") shouldBe true
                    objectMapper.readTree(profileJson).path("spicinessPreference").asInt() shouldBe 5
                }
            }
        }

        given("프로필 응답의 랭킹 요약") {
            `when`("가입 직후 회원이 프로필을 조회하면") {
                then("모든 카운트가 0으로 초기화된 랭킹이 내려온다") {
                    val token = loginAccessToken()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val ranking = objectMapper.readTree(result.contentAsString).path("payload").path("ranking")
                    ranking.path("score").asInt() shouldBe 0
                    ranking.path("tier").asText() shouldBe "newcomer"
                    ranking.path("level").asInt() shouldBe 1
                }
            }

            `when`("메뉴판을 40번 스캔한 회원이 프로필을 조회하면") {
                then("등급·점수·다음 등급이 함께 내려오고 점수 내역은 없다") {
                    val token = loginAccessToken()
                    seedScanCount(40)

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 200
                    val ranking = objectMapper.readTree(result.contentAsString).path("payload").path("ranking")
                    ranking.path("tier").asText() shouldBe "explorer"
                    ranking.path("level").asInt() shouldBe 3
                    ranking.path("score").asInt() shouldBe 80
                    ranking.path("nextTier").asText() shouldBe "regular"
                    ranking.path("pointsToNext").asInt() shouldBe 100
                    ranking.has("breakdown") shouldBe false
                }
            }

            `when`("활동이 없는 회원이 프로필을 조회하면") {
                then("0점 최하 등급으로 내려온다") {
                    val token = loginAccessToken()

                    val result = getMyProfile(token).andReturn().response

                    val ranking = objectMapper.readTree(result.contentAsString).path("payload").path("ranking")
                    ranking.path("tier").asText() shouldBe "newcomer"
                    ranking.path("score").asInt() shouldBe 0
                    ranking.path("pointsToNext").asInt() shouldBe 30
                }
            }
        }

        given("랭킹 상세 조회") {
            `when`("리뷰 8건·고유 음식 6종·스캔 9회인 회원이 조회하면") {
                then("정책 검증 케이스대로 128점 explorer 이고 남은 점수가 52다") {
                    val token = loginAccessToken()
                    seedCounts(scanCount = 9, reviewCount = 8, uniqueReviewedFoodCount = 6)

                    val payload = objectMapper.readTree(getMyRanking(token).andReturn().response.contentAsString)
                        .path("payload")

                    payload.path("score").asInt() shouldBe 128
                    payload.path("tier").asText() shouldBe "explorer"
                    payload.path("pointsToNext").asInt() shouldBe 52

                    val breakdown = payload.path("breakdown")
                    breakdown.path("reviews").path("count").asInt() shouldBe 8
                    breakdown.path("reviews").path("points").asInt() shouldBe 80
                    breakdown.path("diversity").path("count").asInt() shouldBe 6
                    breakdown.path("diversity").path("points").asInt() shouldBe 30
                    breakdown.path("scans").path("count").asInt() shouldBe 9
                    breakdown.path("scans").path("points").asInt() shouldBe 18
                }
            }

            `when`("메뉴판을 40번 스캔한 회원이 조회하면") {
                then("점수 내역이 항목별로 내려오고 합이 총점과 같다") {
                    val token = loginAccessToken()
                    seedScanCount(40)

                    val result = getMyRanking(token).andReturn().response

                    result.status shouldBe 200
                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("tier").asText() shouldBe "explorer"
                    payload.path("score").asInt() shouldBe 80

                    val breakdown = payload.path("breakdown")
                    breakdown.path("scans").path("count").asInt() shouldBe 40
                    breakdown.path("scans").path("points").asInt() shouldBe 80
                    breakdown.path("reviews").path("count").asInt() shouldBe 0
                    breakdown.path("reviews").path("points").asInt() shouldBe 0
                    breakdown.path("diversity").path("count").asInt() shouldBe 0
                    breakdown.path("diversity").path("points").asInt() shouldBe 0

                    val sum = breakdown.path("reviews").path("points").asInt() +
                        breakdown.path("diversity").path("points").asInt() +
                        breakdown.path("scans").path("points").asInt()
                    sum shouldBe payload.path("score").asInt()
                }
            }

            `when`("같은 회원의 프로필 요약과 비교하면") {
                then("등급·레벨·점수·다음 등급·남은 점수가 일치한다") {
                    val token = loginAccessToken()
                    seedScanCount(17)

                    val summary = objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString)
                        .path("payload").path("ranking")
                    val detail = objectMapper.readTree(getMyRanking(token).andReturn().response.contentAsString)
                        .path("payload")

                    detail.path("tier").asText() shouldBe summary.path("tier").asText()
                    detail.path("level").asInt() shouldBe summary.path("level").asInt()
                    detail.path("score").asInt() shouldBe summary.path("score").asInt()
                    detail.path("nextTier").asText() shouldBe summary.path("nextTier").asText()
                    detail.path("pointsToNext").asInt() shouldBe summary.path("pointsToNext").asInt()
                }
            }

            `when`("인증 없이 조회하면") {
                then("401 로 거절된다") {
                    getMyRanking(null).andReturn().response.status shouldBe 401
                }
            }
        }

        given("최고 등급 회원") {
            `when`("누적 점수가 1000점 이상이면") {
                then("프로필·랭킹 상세 모두 다음 등급과 남은 점수가 비어 있다") {
                    val token = loginAccessToken()
                    seedScanCount(500)

                    val summary = objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString)
                        .path("payload").path("ranking")
                    val detail = objectMapper.readTree(getMyRanking(token).andReturn().response.contentAsString)
                        .path("payload")

                    summary.path("tier").asText() shouldBe "korean_at_heart"
                    summary.path("level").asInt() shouldBe 7
                    summary.path("score").asInt() shouldBe 1000
                    summary.path("nextTier").isNull shouldBe true
                    summary.path("pointsToNext").isNull shouldBe true
                    detail.path("nextTier").isNull shouldBe true
                    detail.path("pointsToNext").isNull shouldBe true
                }
            }
        }
    }
}
