package com.kbap.api.core.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import javax.sql.DataSource

@IntegrationTest
class InactiveMemberTokenTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var handlerMappings: List<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping>
    @Autowired private lateinit var jwtFilterRegistration: org.springframework.boot.web.servlet.FilterRegistrationBean<JwtAuthenticationFilter>

    private val mapper = jacksonObjectMapper()

    init {
        val actor = 9690L
        val other = 9691L
        val food = 9690L

        fun exec(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun count(sql: String): Long = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
        }

        fun seed() {
            TestTables.clearAll(dataSource)
            listOf(actor, other).forEach { id ->
                exec(
                    "INSERT INTO member (id, provider, provider_uid, country_code, member_status, onboarding_completed, status, created_at, updated_at) " +
                        "VALUES ($id, 'GOOGLE', 'inactive-token-$id', 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))",
                )
            }
            exec(
                "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, ingredients, " +
                    "content_status, status, created_at, updated_at) VALUES ($food, '토큰음식', '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
            )
            exec("INSERT INTO food_review (id, member_id, food_id, rating, status) VALUES (9690, $other, $food, 5, 'ACTIVE')")
            exec("INSERT INTO food_review (id, member_id, food_id, rating, status) VALUES (9691, $actor, $food, 4, 'ACTIVE')")
            exec("INSERT INTO community_post (id, member_id, content, status, created_at, updated_at) VALUES (9690, $other, '글', 'ACTIVE', NOW(6), NOW(6))")
            exec("INSERT INTO orders (id, member_id, image_path) VALUES (9690, $actor, NULL)")
            exec("INSERT INTO order_item (id, order_id, food_id, menu_name, quantity) VALUES (9690, 9690, $food, '토큰음식', 1)")
        }

        fun withdraw(memberId: Long) = exec("UPDATE member SET status = 'DELETED', provider_uid = CONCAT('DELETED:', id) WHERE id = $memberId")

        fun suspend(memberId: Long) = exec("UPDATE member SET member_status = 'SUSPENDED' WHERE id = $memberId")

        data class Call(val label: String, val method: HttpMethod, val path: String, val body: String?, val written: String)

        val writes = listOf(
            Call("리뷰 좋아요", HttpMethod.POST, "/api/reviews/9690/like?liked=true", null, "SELECT COUNT(*) FROM review_like"),
            Call("리뷰 삭제", HttpMethod.DELETE, "/api/reviews/9691", null, "SELECT COUNT(*) FROM food_review WHERE status = 'DELETED'"),
            Call("게시글 작성", HttpMethod.POST, "/api/community/posts", """{"content":"탈퇴 뒤 글"}""", "SELECT COUNT(*) FROM community_post WHERE member_id = $actor"),
            Call("댓글 작성", HttpMethod.POST, "/api/community/posts/9690/comments", """{"content":"탈퇴 뒤 댓글"}""", "SELECT COUNT(*) FROM community_comment"),
            Call("북마크", HttpMethod.POST, "/api/bookmarks", """{"foodId":$food}""", "SELECT COUNT(*) FROM bookmark"),
            Call("차단", HttpMethod.POST, "/api/members/me/blocks", """{"memberId":$other}""", "SELECT COUNT(*) FROM member_block"),
            Call("문의", HttpMethod.POST, "/api/feedbacks", """{"content":"탈퇴 뒤 문의"}""", "SELECT COUNT(*) FROM feedback"),
            Call("업로드 URL 발급", HttpMethod.POST, "/api/images/upload-url", """{"purpose":"REVIEW","contentType":"image/jpeg","contentLength":1024}""", "SELECT 0"),
            Call("주문 항목 사진 복원", HttpMethod.DELETE, "/api/orders/9690/items/9690/image", null, "SELECT COUNT(*) FROM order_item WHERE updated_at > created_at"),
        )

        fun call(memberId: Long, c: Call) = mockMvc.perform(
            MockMvcRequestBuilders.request(c.method, c.path)
                .header("X-API-Version", "1.0")
                .header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}")
                .header("X-Installation-Id", "inactive-token-install")
                .contentType(MediaType.APPLICATION_JSON)
                .apply { c.body?.let { content(it) } },
        ).andReturn().response

        given("탈퇴한 회원의 아직 유효한 액세스 토큰") {
            writes.forEach { c ->
                `when`("${c.label} 을 호출하면") {
                    then("인증 필터에서 400 MEMBER-003 으로 거절되고 아무것도 기록되지 않는다") {
                        seed()
                        val before = count(c.written)
                        withdraw(actor)

                        val response = call(actor, c)

                        response.status shouldBe 400
                        mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText() shouldBe "MEMBER-003"
                        count(c.written) shouldBe before
                    }
                }
            }
        }

        given("정지된 회원의 액세스 토큰") {
            `when`("쓰기 API 를 호출하면") {
                then("같이 400 MEMBER-003 으로 거절된다") {
                    seed()
                    suspend(actor)

                    val response = call(actor, writes.first())

                    response.status shouldBe 400
                    count("SELECT COUNT(*) FROM review_like") shouldBe 0
                }
            }
        }

        given("활성 회원의 액세스 토큰") {
            `when`("같은 쓰기 API 를 호출하면") {
                then("종전대로 처리된다") {
                    seed()

                    call(actor, writes.first()).status shouldBe 200
                    count("SELECT COUNT(*) FROM review_like") shouldBe 1
                    call(actor, writes.first { it.label == "북마크" }).status shouldBe 200
                }
            }
        }

        given("인증 필터 밖 경로(@AuthMemberIdOrNull)에 탈퇴 회원 토큰") {
            listOf(
                "스캔 이력 검색" to "/api/foods/search?scope=scanned&keyword=%EA%B9%80%EC%B9%98&lang=en",
                "홈" to "/api/home?lang=en",
                "음식 목록" to "/api/foods?lang=en",
            ).forEach { (label, path) ->
                `when`("$label 을 부르면") {
                    then("리졸버가 같은 확인으로 400 MEMBER-003 을 낸다 — 토큰을 보냈으면 회원으로 판정한다") {
                        seed()
                        withdraw(actor)

                        val response = mockMvc.perform(
                            MockMvcRequestBuilders.get(path)
                                .header("X-API-Version", "1.0")
                                .header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(actor, MemberRole.USER)}"),
                        ).andReturn().response

                        response.status shouldBe 400
                        mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText() shouldBe "MEMBER-003"
                    }
                }
            }
        }

        given("@AuthMemberId 를 받는 핸들러 전부") {
            `when`("각 경로를 인증 필터 등록 경로와 대조하면") {
                then("전부 필터 적용 범위 안이다 — 새 엔드포인트가 필터 등록에서 빠지면 여기서 빨개진다") {
                    val filterPatterns = jwtFilterRegistration.urlPatterns
                    fun covered(path: String) = filterPatterns.any { p ->
                        if (p.endsWith("/*")) path == p.removeSuffix("/*") || path.startsWith(p.removeSuffix("*")) else path == p
                    }
                    val uncovered = handlerMappings.flatMap { it.handlerMethods.entries }
                        .filter { (_, method) -> method.methodParameters.any { it.hasParameterAnnotation(AuthMemberId::class.java) } }
                        .flatMap { (info, method) -> info.patternValues.map { "$it ${method.method.name}" } }
                        .filterNot { covered(it.substringBefore(' ').replace(Regex("\\{[^}]+}"), "1")) }

                    uncovered shouldBe emptyList()
                }
            }
        }

        given("관리자 경로에 일반 회원 토큰") {
            `when`("탈퇴한 회원의 토큰으로 관리자 API 를 부르면") {
                then("회원 확인을 건너뛰고 종전대로 403 AUTH-008 이다 — 관리자가 아니라는 답이 더 정확하다") {
                    seed()
                    withdraw(actor)

                    val response = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/admin/dashboard/metrics")
                            .header("X-API-Version", "1.0")
                            .header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(actor, MemberRole.USER)}"),
                    ).andReturn().response

                    response.status shouldBe 403
                }
            }
        }

        given("탈퇴 회원의 기기에서 온 로그아웃") {
            `when`("호출하면") {
                then("인증 필터 대상이 아니라 종전대로 200 이다 — 기기 연결 해제는 해롭지 않다") {
                    seed()
                    withdraw(actor)

                    mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/auth/logout")
                            .header("X-API-Version", "1.0")
                            .header("X-Installation-Id", "inactive-token-install")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"refreshToken":"x"}"""),
                    ).andReturn().response.status shouldBe 200
                }
            }
        }
    }
}
