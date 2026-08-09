package com.kbap.api.member

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.auth.FakeSocialTokenVerifierConfig
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.core.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM member_block")
                    it.execute("DELETE FROM community_comment WHERE parent_id IS NOT NULL")
                    it.execute("DELETE FROM community_comment")
                    it.execute("DELETE FROM community_post")
                    it.execute("DELETE FROM member")
                }
            }
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

        val defaultProfileImagePath = "images/default/profile/profile-default-512.png"

        fun validBody() = mapOf(
            "nickname" to "길동이",
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "spicinessPreference" to "SKIP",
            "profileImageUrl" to defaultProfileImagePath,
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

            `when`("구버전 앱이 폐기된 appLanguage 를 포함해 제출하면") {
                then("그 값은 무시되고 200 으로 정상 처리된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() + ("appLanguage" to "fr")).andReturn().response

                    result.status shouldBe 200
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
                    payload.has("appLanguage") shouldBe false
                    payload.path("onboardingCompleted").asBoolean() shouldBe true
                    payload.path("provider").asText() shouldBe "GOOGLE"
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
                    payload.path("provider").asText() shouldBe "GOOGLE"
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

            `when`("닉네임·국가만 보내면") {
                then("기피 성분이 삭제되지 않고 그대로 유지된다") {
                    val token = onboardedToken()

                    val result = updateProfile(
                        token,
                        mapOf("nickname" to "새닉", "countryCode" to "JP"),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.path("countryCode").asText() shouldBe "JP"
                    payload.path("avoidanceSubstanceCodes").map { it.asText() }.toSet() shouldBe setOf("EGG", "MILK")
                }
            }

            `when`("구버전 앱이 폐기된 appLanguage 를 포함해 수정하면") {
                then("그 값은 무시되고 나머지 항목만 반영된다") {
                    val token = onboardedToken()

                    val result = updateProfile(
                        token,
                        mapOf("nickname" to "새닉", "appLanguage" to "ja"),
                    ).andReturn().response

                    result.status shouldBe 200
                    val payload = profilePayload(token)
                    payload.path("nickname").asText() shouldBe "새닉"
                    payload.has("appLanguage") shouldBe false
                }
            }

            `when`("기피 성분만 보내면") {
                then("기피 성분만 교체되고 닉네임·국가는 유지된다") {
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
                            "spicinessPreference" to "MILD",
                            "profileImageUrl" to defaultProfileImagePath,
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

        given("프로필 부분 수정 — 맵기 미전송 보존") {
            `when`("닉네임만 수정하면") {
                then("맵기 선호도는 보존된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andExpect { status { isOk() } }

                    updateProfile(token, mapOf("nickname" to "새닉")).andExpect { status { isOk() } }

                    memberColumn("google-sub-fixed", "spiciness_preference") shouldBe "SKIP"
                }
            }
        }

        given("프로필 부분 수정 — 사진·맵기 교체와 유지") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            fun onboardedWithImageToken(): String {
                val token = loginAccessToken()
                submitOnboarding(
                    token,
                    validBody() + mapOf(
                        "profileImageUrl" to "profiles/origin.jpg",
                        "spicinessPreference" to "MEDIUM",
                    ),
                ).andExpect { status { isOk() } }
                return token
            }

            `when`("새 사진 경로만 담아 수정하면") {
                then("사진은 교체되고 나머지 프로필 값은 유지된다") {
                    val token = onboardedWithImageToken()

                    updateProfile(token, mapOf("profileImageUrl" to "profiles/new.jpg"))
                        .andExpect { status { isOk() } }

                    val payload = profilePayload(token)
                    payload.path("profileImageUrl").asText() shouldBe "https://cdn.test/profiles/new.jpg"
                    payload.path("nickname").asText() shouldBe "길동이"
                    payload.path("spicinessPreference").asText() shouldBe "MEDIUM"
                }
            }

            `when`("닉네임만 담아 수정하면") {
                then("사진과 맵기는 기존 값 그대로 유지된다") {
                    val token = onboardedWithImageToken()

                    updateProfile(token, mapOf("nickname" to "새닉")).andExpect { status { isOk() } }

                    val payload = profilePayload(token)
                    payload.path("profileImageUrl").asText() shouldBe "https://cdn.test/profiles/origin.jpg"
                    payload.path("spicinessPreference").asText() shouldBe "MEDIUM"
                }
            }

            `when`("맵기 EXTREME 만 담아 수정하면") {
                then("맵기는 교체되고 사진·닉네임은 유지된다") {
                    val token = onboardedWithImageToken()

                    updateProfile(token, mapOf("spicinessPreference" to "EXTREME")).andExpect { status { isOk() } }

                    val payload = profilePayload(token)
                    payload.path("spicinessPreference").asText() shouldBe "EXTREME"
                    payload.path("profileImageUrl").asText() shouldBe "https://cdn.test/profiles/origin.jpg"
                    payload.path("nickname").asText() shouldBe "길동이"
                }
            }

            `when`("전체 URL 사진을 담아 수정하면") {
                then("400 MEMBER-008 로 거절되고 아무 필드도 변경되지 않는다") {
                    val token = onboardedWithImageToken()

                    val result = updateProfile(token, mapOf("profileImageUrl" to "http://cdn.example.com/x.jpg"))
                        .andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-008"
                    profilePayload(token).path("profileImageUrl").asText() shouldBe
                        "https://cdn.test/profiles/origin.jpg"
                }
            }

            `when`("6단계에 없는 맵기를 담아 수정하면") {
                then("400 MEMBER-009 로 거절되고 아무 필드도 변경되지 않는다") {
                    val token = onboardedWithImageToken()

                    val result = updateProfile(token, mapOf("spicinessPreference" to "SUPER_HOT")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-009"
                    profilePayload(token).path("spicinessPreference").asText() shouldBe "MEDIUM"
                }
            }

            `when`("사진에 null 을 명시해 수정하면") {
                then("미전송과 동일하게 기존 사진이 유지된다") {
                    val token = onboardedWithImageToken()

                    updateProfile(token, mapOf("profileImageUrl" to null)).andExpect { status { isOk() } }

                    profilePayload(token).path("profileImageUrl").asText() shouldBe
                        "https://cdn.test/profiles/origin.jpg"
                }
            }

            `when`("사진에 빈 문자열을 담아 수정하면") {
                then("400 MEMBER-008 로 거절되고 기존 사진이 유지된다") {
                    val token = onboardedWithImageToken()

                    val result = updateProfile(token, mapOf("profileImageUrl" to "")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-008"
                    profilePayload(token).path("profileImageUrl").asText() shouldBe
                        "https://cdn.test/profiles/origin.jpg"
                }
            }

            `when`("사진에 공백 문자열을 담아 수정하면") {
                then("빈 문자열과 동일하게 400 MEMBER-008 로 거절된다") {
                    val token = onboardedWithImageToken()

                    val result = updateProfile(token, mapOf("profileImageUrl" to "   ")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-008"
                    profilePayload(token).path("profileImageUrl").asText() shouldBe
                        "https://cdn.test/profiles/origin.jpg"
                }
            }
        }

        given("온보딩의 프로필 사진·맵기 등록") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            `when`("사진 경로와 맵기 7 을 포함해 온보딩하면") {
                then("DB 엔 경로만 저장되고 조회 응답엔 CDN 도메인이 조합된 완전한 URL 이 담긴다") {
                    val token = loginAccessToken()

                    submitOnboarding(
                        token,
                        validBody() + mapOf(
                            "profileImageUrl" to "profiles/abc.jpg",
                            "spicinessPreference" to "HOT",
                        ),
                    ).andExpect { status { isOk() } }

                    memberColumn("google-sub-fixed", "profile_image_url") shouldBe "profiles/abc.jpg"

                    val payload = profilePayload(token)
                    payload.path("profileImageUrl").asText() shouldBe "https://cdn.test/profiles/abc.jpg"
                    payload.path("spicinessPreference").asText() shouldBe "HOT"
                }
            }

            `when`("레거시 절대 URL 이 저장된 회원이 조회하면") {
                then("도메인을 덧붙이지 않고 저장값 그대로 반환한다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andExpect { status { isOk() } }
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.execute(
                                "UPDATE member SET profile_image_url = 'https://legacy-cdn.example.com/old.jpg' " +
                                    "WHERE provider_uid = 'google-sub-fixed'",
                            )
                        }
                    }

                    profilePayload(token).path("profileImageUrl").asText() shouldBe
                        "https://legacy-cdn.example.com/old.jpg"
                }
            }

            `when`("사진을 생략하고 온보딩하면") {
                then("400 과 COMMON-002 로 거절되고 온보딩은 완료되지 않는다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() - "profileImageUrl").andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "COMMON-002"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }

            `when`("사진에 null 을 명시해 온보딩하면") {
                then("400 과 COMMON-002 로 거절된다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() + ("profileImageUrl" to null))
                        .andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "COMMON-002"
                }
            }

            `when`("닉네임을 생략하고 온보딩하면") {
                then("400 과 COMMON-002 로 거절되고 온보딩·닉네임이 저장되지 않는다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() - "nickname").andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "COMMON-002"
                    memberColumn("google-sub-fixed", "nickname") shouldBe null
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }

            `when`("닉네임에 null 을 명시해 온보딩하면") {
                then("400 과 COMMON-002 로 거절되고 온보딩·닉네임이 저장되지 않는다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() + ("nickname" to null))
                        .andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "COMMON-002"
                    memberColumn("google-sub-fixed", "nickname") shouldBe null
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }

            `when`("기본 이미지 경로로 온보딩하면") {
                then("200 으로 저장되고 조회 응답엔 CDN 도메인이 조합된 기본 이미지 URL 이 담긴다") {
                    val token = loginAccessToken()

                    submitOnboarding(token, validBody()).andExpect { status { isOk() } }

                    val payload = profilePayload(token)
                    payload.path("profileImageUrl").asText() shouldBe
                        "https://cdn.test/images/default/profile/profile-default-512.png"
                }
            }

            `when`("빈 문자열 사진 URL 로 온보딩하면") {
                then("400 MEMBER-008 로 거절되고 온보딩은 완료되지 않는다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() + ("profileImageUrl" to "  "))
                        .andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-008"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }
        }

        given("온보딩의 프로필 사진 경로 검증 — 저장 없이 400") {
            listOf(
                "https 전체 URL" to "https://cdn.example.com/p.jpg",
                "http 전체 URL" to "http://cdn.example.com/p.jpg",
                "대문자 스킴 전체 URL" to "HTTPS://cdn.example.com/p.jpg",
                "512자를 넘는 경로" to "a".repeat(513),
            ).forEach { (label, path) ->
                `when`(label + "을 제출하면") {
                    then("400 MEMBER-008 로 거절되고 아무것도 저장되지 않는다") {
                        val token = loginAccessToken()

                        val result = submitOnboarding(token, validBody() + ("profileImageUrl" to path))
                            .andReturn().response

                        result.status shouldBe 400
                        result.contentAsString shouldContain "MEMBER-008"
                        memberColumn("google-sub-fixed", "nickname") shouldBe null
                        memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    }
                }
            }
        }

        given("온보딩의 맵기 단계 검증 — 저장 없이 400") {
            listOf("SUPER_HOT", "hot", 7).forEach { spiciness ->
                `when`("6단계에 없는 맵기 $spiciness 를 제출하면") {
                    then("400 MEMBER-009 로 거절되고 아무것도 저장되지 않는다") {
                        val token = loginAccessToken()

                        val result = submitOnboarding(token, validBody() + ("spicinessPreference" to spiciness))
                            .andReturn().response

                        result.status shouldBe 400
                        result.contentAsString shouldContain "MEMBER-009"
                        memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    }
                }
            }
        }

        given("맵기 선호 미설정(SKIP) — 온보딩 저장·조회") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            `when`("맵기 선호를 생략하고 온보딩하면") {
                then("400 과 COMMON-002 를 반환하고 온보딩은 완료되지 않는다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() - "spicinessPreference").andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "\"success\":false"
                    result.contentAsString shouldContain "COMMON-002"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }

            `when`("맵기 선호로 SKIP 을 명시해 온보딩하면") {
                then("200 으로 저장되고 조회 시 SKIP 으로 반환된다") {
                    val token = loginAccessToken()

                    submitOnboarding(token, validBody() + ("spicinessPreference" to "SKIP"))
                        .andExpect { status { isOk() } }

                    profilePayload(token).path("spicinessPreference").asText() shouldBe "SKIP"
                }
            }

            `when`("맵기 선호로 HOT 을 보내 온보딩하면") {
                then("조회 시 그 단계가 그대로 반환된다") {
                    val token = loginAccessToken()

                    submitOnboarding(token, validBody() + ("spicinessPreference" to "HOT"))
                        .andExpect { status { isOk() } }

                    profilePayload(token).path("spicinessPreference").asText() shouldBe "HOT"
                }
            }
        }

        given("맵기 선호 미설정(SKIP) — 프로필 수정") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            fun onboardWithSpiciness(spiciness: String): String {
                val token = loginAccessToken()
                submitOnboarding(token, validBody() + ("spicinessPreference" to spiciness))
                    .andExpect { status { isOk() } }
                return token
            }

            `when`("맵기 MEDIUM 회원이 프로필 수정에서 SKIP 을 명시 전송하면") {
                then("맵기 선호가 미설정(SKIP)으로 복귀한다") {
                    val token = onboardWithSpiciness("MEDIUM")

                    updateProfile(token, mapOf("spicinessPreference" to "SKIP")).andExpect { status { isOk() } }

                    profilePayload(token).path("spicinessPreference").asText() shouldBe "SKIP"
                }
            }

            `when`("맵기 HOT 회원이 맵기를 생략하고 다른 필드만 수정하면") {
                then("맵기 선호는 HOT 으로 유지된다") {
                    val token = onboardWithSpiciness("HOT")

                    updateProfile(token, mapOf("nickname" to "새닉")).andExpect { status { isOk() } }

                    profilePayload(token).path("spicinessPreference").asText() shouldBe "HOT"
                }
            }

            `when`("미설정(SKIP) 회원이 단계 문자열을 보내면") {
                then("그 값으로 교체된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andExpect { status { isOk() } }

                    updateProfile(token, mapOf("spicinessPreference" to "EXTREME")).andExpect { status { isOk() } }

                    profilePayload(token).path("spicinessPreference").asText() shouldBe "EXTREME"
                }
            }
        }

        given("맵기 선호 6단계 외 값 거절 — 메시지에 단계 목록 반영") {
            fun profilePayload(token: String) =
                objectMapper.readTree(getMyProfile(token).andReturn().response.contentAsString).path("payload")

            `when`("온보딩에서 정수 -2 를 보내면") {
                then("400 MEMBER-009 로 거절되고 메시지에 단계 목록이 담긴다") {
                    val token = loginAccessToken()

                    val result = submitOnboarding(token, validBody() + ("spicinessPreference" to -2))
                        .andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-009"
                    result.contentAsString shouldContain "SKIP·NONE·MILD·MEDIUM·HOT·EXTREME"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                }
            }

            `when`("프로필 수정에서 SUPER_HOT 을 보내면") {
                then("400 MEMBER-009 로 거절되고 메시지에 단계 목록이 담기며 맵기는 유지된다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody() + ("spicinessPreference" to "MEDIUM"))
                        .andExpect { status { isOk() } }

                    val result = updateProfile(token, mapOf("spicinessPreference" to "SUPER_HOT")).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "MEMBER-009"
                    result.contentAsString shouldContain "SKIP·NONE·MILD·MEDIUM·HOT·EXTREME"
                    profilePayload(token).path("spicinessPreference").asText() shouldBe "MEDIUM"
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
