package com.kbap.app.api.scenario

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.application.auth.token.AuthTokenProperties
import com.kbap.domain.member.model.MemberRole
import com.kbap.infra.auth.token.JwtTokenIssuer
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.UUID

data class 응답(val 상태코드: Int, val payload: JsonNode, val code: String?)

class ScenarioApiDriver(
    private val mockMvc: MockMvc,
    여정접두어: String,
    private val authTokenProperties: AuthTokenProperties? = null,
) {
    private val objectMapper = jacksonObjectMapper()

    val idToken: String = "scenario-$여정접두어-${UUID.randomUUID()}"
    var accessToken: String = ""
    var refreshToken: String = ""
    var objectKey: String = ""
    var foodId: Long = 0

    fun 회원가입한다(): Boolean = 로그인()

    fun 재로그인한다(): Boolean = 로그인()

    private fun 로그인(): Boolean {
        val payload = payload(post("/api/v1/auth/login", mapOf("idToken" to idToken), authenticated = false))
        accessToken = payload.path("accessToken").asText()
        refreshToken = payload.path("refreshToken").asText()
        return payload.path("newMember").asBoolean()
    }

    fun 온보딩한다(
        nickname: String = "시나리오사용자",
        avoidanceSubstanceCodes: List<String> = emptyList(),
        countryCode: String = "US",
        appLanguage: String = "en",
        spicinessPreference: Int = 3,
    ): Int = post(
        "/api/v1/members/me/onboarding",
        mapOf(
            "nickname" to nickname,
            "avoidanceSubstanceCodes" to avoidanceSubstanceCodes,
            "countryCode" to countryCode,
            "appLanguage" to appLanguage,
            "spicinessPreference" to spicinessPreference,
        ),
    ).status

    fun 홈을_조회한다(): JsonNode = payload(get("/api/v1/home"))

    fun 음식을_검색한다(keyword: String): JsonNode {
        val items = payload(get("/api/v1/foods/search", "keyword" to keyword)).path("items")
        foodId = items.firstOrNull()?.path("foodId")?.asLong() ?: 0
        return items
    }

    fun 음식_상세를_조회한다(): JsonNode = payload(get("/api/v1/foods/$foodId"))

    fun 북마크한다(): Int = post("/api/v1/bookmarks", mapOf("foodId" to foodId)).status

    fun 북마크_목록을_조회한다(): JsonNode = payload(get("/api/v1/bookmarks")).path("items")

    fun 프로필을_조회한다(): 응답 = 응답으로(get("/api/v1/members/me/profile"))

    fun 만료된_액세스토큰으로_프로필을_조회한다(): 응답 {
        val properties = requireNotNull(authTokenProperties) { "만료 토큰 스텝은 AuthTokenProperties 주입이 필요합니다" }
        val expired = JwtTokenIssuer(properties.copy(accessTtl = Duration.ofMinutes(-1)))
            .issueAccessToken(memberId = 0L, role = MemberRole.USER)
        val response = mockMvc.get("/api/v1/members/me/profile") {
            header("Authorization", "Bearer $expired")
        }.andReturn().response
        return 응답으로(response)
    }

    fun 토큰을_갱신한다(): String {
        val 구토큰 = refreshToken
        val payload = payload(post("/api/v1/auth/refresh", mapOf("refreshToken" to 구토큰), authenticated = false))
        accessToken = payload.path("accessToken").asText()
        refreshToken = payload.path("refreshToken").asText()
        return 구토큰
    }

    fun 구_리프레시토큰으로_갱신을_시도한다(구토큰: String): 응답 =
        응답으로(post("/api/v1/auth/refresh", mapOf("refreshToken" to 구토큰), authenticated = false))

    fun 로그아웃한다(): Int = post("/api/v1/auth/logout", mapOf("refreshToken" to refreshToken)).status

    fun 업로드URL을_발급받는다(contentType: String, contentLength: Long): JsonNode {
        val payload = payload(
            post(
                "/api/v1/images/upload-url",
                mapOf("purpose" to "MENU_SCAN", "contentType" to contentType, "contentLength" to contentLength),
            ),
        )
        objectKey = payload.path("objectKey").asText()
        return payload
    }

    fun 업로드를_완료한다(contentType: String, size: Long): Int =
        post("/api/v1/images/complete", mapOf("path" to objectKey, "contentType" to contentType, "size" to size)).status

    fun 스캔한다(vararg 메뉴명: String): JsonNode {
        val items = 메뉴명.mapIndexed { idx, name -> mapOf("idx" to idx, "rawMenuName" to name) }
        return payload(post("/api/v1/scans", mapOf("imagePath" to objectKey, "items" to items))).path("results")
    }

    fun 탈퇴한다(): Int =
        mockMvc.patch("/api/v1/auth/withdraw") {
            header("Authorization", "Bearer $accessToken")
        }.andReturn().response.status

    private fun get(path: String, vararg params: Pair<String, String>): MockHttpServletResponse =
        mockMvc.get(path) {
            header("Authorization", "Bearer $accessToken")
            params.forEach { (name, value) -> param(name, value) }
        }.andReturn().response

    private fun post(path: String, body: Map<String, Any?>, authenticated: Boolean = true): MockHttpServletResponse =
        mockMvc.post(path) {
            if (authenticated) header("Authorization", "Bearer $accessToken")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andReturn().response

    private fun payload(response: MockHttpServletResponse): JsonNode =
        objectMapper.readTree(response.contentAsString).path("payload")

    private fun 응답으로(response: MockHttpServletResponse): 응답 {
        val body = objectMapper.readTree(response.contentAsString)
        return 응답(
            상태코드 = response.status,
            payload = body.path("payload"),
            code = body.path("code").takeIf { !it.isMissingNode && !it.isNull }?.asText(),
        )
    }
}
