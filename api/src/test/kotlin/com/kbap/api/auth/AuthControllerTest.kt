package com.kbap.api.auth

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.core.error.ErrorCode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.member.model.SocialProvider
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class AuthControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var memberRepository: com.kbap.common.domain.member.MemberJpaRepository

    @Autowired
    private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @Autowired
    private lateinit var uploadedImageService: com.kbap.api.image.UploadedImageService

    @Autowired
    private lateinit var orderRepository: com.kbap.common.domain.order.OrderJpaRepository

    @Autowired
    private lateinit var accountDeleter: FakeSocialAccountDeleter

    init {
        val objectMapper = jacksonObjectMapper()

        fun clearMembers() = TestTables.clearAll(dataSource)

        fun countMembers(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM member").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun suspendAll() {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("UPDATE member SET member_status = 'SUSPENDED'") }
            }
        }

        fun memberColumn(providerUid: String, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun login(idToken: String = FakeSocialTokenVerifier.DEFAULT_SUB) =
            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to idToken))
            }

        fun bodyToken(response: MockHttpServletResponse, field: String): String =
            objectMapper.readTree(response.contentAsString).path("payload").path(field).asText()

        fun refresh(refreshToken: String?) =
            mockMvc.post("/api/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
            }

        fun logout(refreshToken: String?) =
            mockMvc.post("/api/auth/logout") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
            }

        fun withdraw(token: String?) =
            mockMvc.patch("/api/auth/withdraw") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun loginAccessToken(): String = bodyToken(login().andReturn().response, "accessToken")

        fun getMyProfile(token: String?) =
            mockMvc.get("/api/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun submitOnboarding(token: String, body: Map<String, Any?>) =
            mockMvc.post("/api/members/me/onboarding") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun validBody() = mapOf(
            "nickname" to "길동이",
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "spicinessPreference" to "MILD",
            "profileImageUrl" to "images/default/profile/profile-default-512.png",
        )

        fun columnById(id: Long, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        beforeContainer {
            clearMembers()
            verifier.reset()
            accountDeleter.reset()
        }

        given("유효한 소셜 토큰의 미가입 사용자") {
            `when`("로그인하면") {
                then("가입되고 신규 회원 플래그와 함께 access·refresh 토큰이 응답 본문으로 내려온다") {
                    val result = login().andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    result.contentAsString shouldContain "\"newMember\":true"

                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("accessToken").asText().isNotBlank() shouldBe true
                    payload.path("refreshToken").asText().isNotBlank() shouldBe true
                    payload.has("memberId") shouldBe false
                    result.getHeaders("Set-Cookie").isEmpty() shouldBe true
                }
            }

            `when`("로그인하면") {
                then("회원이 신원과 함께 저장되고 온보딩 대기·활성 상태로 가입된다") {
                    login().andReturn()

                    countMembers() shouldBe 1
                    memberColumn("google-sub-fixed", "provider") shouldBe "GOOGLE"
                    memberColumn("google-sub-fixed", "email") shouldBe "user@gmail.com"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    memberColumn("google-sub-fixed", "member_status") shouldBe "ACTIVE"
                    memberColumn("google-sub-fixed", "status") shouldBe "ACTIVE"
                }
            }
        }

        given("이미 가입된 회원") {
            `when`("같은 소셜 계정으로 다시 로그인하면") {
                then("신규 회원이 아니며 회원이 중복 생성되지 않는다") {
                    login().andReturn()

                    val result = login().andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"newMember\":false"
                    countMembers() shouldBe 1
                }
            }
        }

        given("검증에 실패하는 소셜 토큰") {
            `when`("로그인하면") {
                then("401 로 거절되고 회원이 생성되지 않는다") {
                    verifier.failWith(ErrorCode.INVALID_SOCIAL_TOKEN)

                    val result = login("forged-token").andReturn().response

                    result.status shouldBe 401
                    result.contentAsString shouldContain "\"success\":false"
                    countMembers() shouldBe 0
                }
            }
        }

        given("지원하지 않는 provider 토큰") {
            `when`("로그인하면") {
                then("401 로 거절된다") {
                    verifier.failWith(ErrorCode.UNSUPPORTED_PROVIDER)

                    login("kakao-token").andReturn().response.status shouldBe 401
                }
            }
        }

        given("idToken 이 비어 있는 요청") {
            `when`("로그인하면") {
                then("400 으로 거절된다") {
                    login("").andReturn().response.status shouldBe 400
                }
            }
        }

        given("정지된 회원") {
            `when`("같은 소셜 계정으로 로그인하면") {
                then("로그인이 거부되고 신규 회원이 생성되지 않는다") {
                    login().andReturn()
                    suspendAll()

                    val result = login().andReturn().response

                    (result.status >= 400) shouldBe true
                    result.contentAsString shouldContain "\"success\":false"
                    countMembers() shouldBe 1
                }
            }
        }

        given("발급된 access 토큰") {
            `when`("본문을 디코딩하면") {
                then("개인정보 클레임이 담기지 않는다") {
                    val token = bodyToken(login().andReturn().response, "accessToken")
                    token.shouldNotBeNull()

                    val payload = String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))
                    payload.contains("gmail.com") shouldBe false
                }
            }
        }

        given("로그인으로 받은 refresh 토큰") {
            `when`("재발급하면") {
                then("access·refresh 가 모두 새 값으로 응답 본문에 내려온다(rotation)") {
                    val loginResponse = login().andReturn().response
                    val oldRefresh = bodyToken(loginResponse, "refreshToken")

                    val response = refresh(oldRefresh).andReturn().response

                    response.status shouldBe 200
                    val newAccess = bodyToken(response, "accessToken")
                    val newRefresh = bodyToken(response, "refreshToken")
                    (newRefresh != oldRefresh) shouldBe true
                    newAccess.isNotBlank() shouldBe true
                }
            }

            `when`("재발급 후 이전 refresh 토큰을 다시 쓰면") {
                then("401 로 거절된다(rotation 으로 폐기됨)") {
                    val oldRefresh = bodyToken(login().andReturn().response, "refreshToken")
                    refresh(oldRefresh).andReturn()

                    refresh(oldRefresh).andReturn().response.status shouldBe 401
                }
            }
        }

        given("refresh 토큰 없는 재발급 요청") {
            `when`("재발급하면") {
                then("400 으로 거절된다") {
                    refresh(null).andReturn().response.status shouldBe 400
                }
            }
        }

        given("조작된 refresh 토큰") {
            `when`("재발급하면") {
                then("401 로 거절된다") {
                    refresh("forged.refresh.token").andReturn().response.status shouldBe 401
                }
            }
        }

        fun deleteAllMembers() {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("DELETE FROM member") }
            }
        }

        given("refresh 회전 시 회원 상태 검증") {
            `when`("ACTIVE 회원이 재발급하면") {
                then("200 으로 회전된다") {
                    val oldRefresh = bodyToken(login().andReturn().response, "refreshToken")

                    refresh(oldRefresh).andReturn().response.status shouldBe 200
                }
            }

            `when`("세션의 회원이 DB 에서 사라진 뒤 재발급하면") {
                then("401(AUTH-005) 로 거절되고 세션이 회전되지 않는다") {
                    val oldRefresh = bodyToken(login().andReturn().response, "refreshToken")
                    deleteAllMembers()

                    val response = refresh(oldRefresh).andReturn().response

                    response.status shouldBe 401
                    objectMapper.readTree(response.contentAsString).path("code").asText() shouldBe "AUTH-005"
                }
            }

            `when`("세션의 회원이 정지(SUSPENDED)된 뒤 재발급하면") {
                then("401(AUTH-005) 로 거절된다") {
                    val oldRefresh = bodyToken(login().andReturn().response, "refreshToken")
                    suspendAll()

                    val response = refresh(oldRefresh).andReturn().response

                    response.status shouldBe 401
                    objectMapper.readTree(response.contentAsString).path("code").asText() shouldBe "AUTH-005"
                }
            }
        }

        given("로그인된 회원") {
            `when`("로그아웃하면") {
                then("세션이 폐기되어 그 refresh 로는 재발급할 수 없다") {
                    val refreshToken = bodyToken(login().andReturn().response, "refreshToken")

                    logout(refreshToken).andReturn().response.status shouldBe 200

                    refresh(refreshToken).andReturn().response.status shouldBe 401
                }
            }

            `when`("refresh 토큰 없이 로그아웃하면") {
                then("200 으로 멱등 처리된다") {
                    logout(null).andReturn().response.status shouldBe 200
                }
            }

            `when`("이미 로그아웃한 refresh 토큰으로 다시 로그아웃하면") {
                then("200 으로 멱등 처리된다") {
                    val refreshToken = bodyToken(login().andReturn().response, "refreshToken")
                    logout(refreshToken).andReturn().response.status shouldBe 200

                    logout(refreshToken).andReturn().response.status shouldBe 200
                }
            }
        }

        given("회원 탈퇴") {
            `when`("로그인한 회원이 탈퇴하면") {
                then("200 으로 응답하고 인증 제공자 계정 삭제 후 회원 행이 소프트 삭제된다") {
                    val token = loginAccessToken()
                    val id = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)

                    val result = withdraw(token).andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    accountDeleter.deleted shouldBe
                        listOf(SocialProvider.GOOGLE to FakeSocialTokenVerifier.DEFAULT_SUB)
                    columnById(id, "provider_uid") shouldBe "DELETED:$id"
                    columnById(id, "status") shouldBe "DELETED"
                }
            }

            `when`("주문 위치 정보가 있는 회원이 탈퇴하면") {
                then("그 회원 주문의 위치 8컬럼(좌표·주소·식당 스냅샷)이 NULL 이 되고 주문 행·항목은 남는다 — 다른 회원의 주문은 그대로다") {
                    val token = loginAccessToken()
                    val id = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    val otherId = dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.executeUpdate(
                                "INSERT INTO member (provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) " +
                                    "VALUES ('GOOGLE', 'withdraw-location-other', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE id = id",
                            )
                        }
                        memberIdOf("withdraw-location-other")
                    }
                    val locationColumns = listOf(
                        "latitude", "longitude", "road_address",
                        "place_source", "place_external_id", "place_name", "place_address", "place_language",
                    )
                    fun seedOrder(memberId: Long, imagePath: String?, status: String = "ACTIVE"): Long =
                        dataSource.connection.use { c ->
                            c.prepareStatement(
                                "INSERT INTO orders (member_id, image_path, latitude, longitude, road_address, place_source, " +
                                    "place_external_id, place_name, place_address, place_language, status) " +
                                    "VALUES (?, ?, 37.5636000, 126.9834000, '서울 중구 소공로 51', 'GOOGLE_PLACE', 'ChIJwithdraw', '백년옥', " +
                                    "'서울 중구 소공로 51', 'ko', ?)",
                                java.sql.Statement.RETURN_GENERATED_KEYS,
                            ).use { ps ->
                                ps.setLong(1, memberId)
                                ps.setString(2, imagePath)
                                ps.setString(3, status)
                                ps.executeUpdate()
                                ps.generatedKeys.use { rs -> rs.next(); rs.getLong(1) }
                            }
                        }
                    fun locationOf(orderId: Long): List<String?> =
                        dataSource.connection.use { c ->
                            c.prepareStatement("SELECT ${locationColumns.joinToString()} FROM orders WHERE id = ?").use { ps ->
                                ps.setLong(1, orderId)
                                ps.executeQuery().use { rs -> rs.next(); locationColumns.indices.map { rs.getString(it + 1) } }
                            }
                        }
                    fun itemCountOf(orderId: Long): Int =
                        dataSource.connection.use { c ->
                            c.prepareStatement("SELECT COUNT(*) FROM order_item WHERE order_id = ?").use { ps ->
                                ps.setLong(1, orderId)
                                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                            }
                        }
                    val mine = seedOrder(id, "scan/withdraw/mine.jpg")
                    val mineDeleted = seedOrder(id, null, status = "DELETED")
                    val others = seedOrder(otherId, "scan/withdraw/others.jpg")
                    dataSource.connection.use { c ->
                        c.createStatement().use {
                            it.executeUpdate(
                                "INSERT INTO food (id, korean_name, description, spiciness, name_translations, description_translations, " +
                                    "ingredients, content_status, status, created_at, updated_at) VALUES (9570, '탈퇴주문음식', '설명', 0, '{}', '{}', '[]', " +
                                    "'READY', 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE id = id",
                            )
                            it.executeUpdate("INSERT INTO order_item (order_id, food_id, menu_name, quantity, price) VALUES ($mine, 9570, '탈퇴주문음식', 2, 5000)")
                        }
                    }

                    withdraw(token).andReturn().response.status shouldBe 200

                    locationOf(mine) shouldBe List(locationColumns.size) { null }
                    locationOf(mineDeleted) shouldBe List(locationColumns.size) { null }
                    locationOf(others).none { it == null } shouldBe true
                    itemCountOf(mine) shouldBe 1
                    columnById(id, "status") shouldBe "DELETED"
                }
            }

            `when`("탈퇴 후 같은 access 토큰으로 프로필을 조회하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    withdraw(token).andReturn()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "해당 회원을 찾을 수 없습니다"
                }
            }

            `when`("이미 탈퇴한 회원이 다시 탈퇴를 요청하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    withdraw(token).andReturn()

                    withdraw(token).andReturn().response.status shouldBe 400
                }
            }

            `when`("인증 없이 탈퇴를 요청하면") {
                then("401 로 거절되고 인증 제공자 계정도 지워지지 않는다") {
                    loginAccessToken()

                    withdraw(null).andReturn().response.status shouldBe 401

                    accountDeleter.deleted.isEmpty() shouldBe true
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "status") shouldBe "ACTIVE"
                }
            }

            `when`("인증 제공자 계정 삭제가 실패하면") {
                then("500 으로 거절되고 회원 행은 활성 상태로 남는다") {
                    val token = loginAccessToken()
                    accountDeleter.fail()

                    withdraw(token).andReturn().response.status shouldBe 500

                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "status") shouldBe "ACTIVE"
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "member_status") shouldBe "ACTIVE"
                }
            }
        }

        given("로그인 오퍼레이션 문서") {
            `when`("api-docs 를 보면") {
                then("403 응답과 오퍼레이션 에러 표(@ApiErrors) 양쪽에 정지 회원 거절 MEMBER-013 이 실려 있다") {
                    val login = objectMapper.readTree(mockMvc.get("/v3/api-docs").andReturn().response.contentAsString)
                        .path("paths").path("/api/auth/login").path("post")
                    login.path("responses").path("403").path("description").asText().contains("MEMBER-013") shouldBe true
                    login.path("description").asText().contains("MEMBER-013") shouldBe true
                }
            }
        }

        given("정지된 회원의 로그인") {
            `when`("같은 소셜 계정으로 로그인하면") {
                then("403 MEMBER-013 으로 거절되고 새 회원이 생기지 않는다 — 가입 중복(MEMBER-001)으로 오인되지 않는다") {
                    loginAccessToken()
                    val id = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    dataSource.connection.use { c ->
                        c.prepareStatement("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ?").use { ps -> ps.setLong(1, id); ps.executeUpdate() }
                    }
                    val before = countMembers()

                    val response = login().andReturn().response

                    response.status shouldBe 403
                    response.contentAsString shouldContain "MEMBER-013"
                    countMembers() shouldBe before
                }
            }

            `when`("사전 확인 직후 정지돼 가입 시도가 소셜 신원 유니크에 걸리면") {
                then("재조회에서도 정지를 가려 MEMBER-013 으로 거절한다 — 가입 중복으로 오인되지 않는다") {
                    loginAccessToken()
                    val id = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    dataSource.connection.use { c ->
                        c.prepareStatement("UPDATE member SET member_status = 'SUSPENDED' WHERE id = ?").use { ps -> ps.setLong(1, id); ps.executeUpdate() }
                    }
                    var firstCheck = true
                    val racing = java.lang.reflect.Proxy.newProxyInstance(
                        com.kbap.common.domain.member.MemberJpaRepository::class.java.classLoader,
                        arrayOf(com.kbap.common.domain.member.MemberJpaRepository::class.java),
                    ) { _, method, args ->
                        if (method.name == "existsByProviderAndProviderUidAndMemberStatus" && firstCheck) {
                            firstCheck = false
                            false
                        } else {
                            try {
                                method.invoke(memberRepository, *(args ?: emptyArray()))
                            } catch (e: java.lang.reflect.InvocationTargetException) {
                                throw e.targetException
                            }
                        }
                    } as com.kbap.common.domain.member.MemberJpaRepository
                    val service = com.kbap.api.member.MemberService(racing, uploadedImageService, orderRepository, "", "")

                    val error = io.kotest.assertions.throwables.shouldThrow<com.kbap.common.core.error.BusinessException> {
                        service.findOrSignUp(com.kbap.common.domain.member.model.SocialIdentity(SocialProvider.GOOGLE, FakeSocialTokenVerifier.DEFAULT_SUB, null))
                    }

                    error.errorCode shouldBe ErrorCode.MEMBER_SUSPENDED_LOGIN
                }
            }

            `when`("다른 로그인이 같은 소셜 계정 가입을 먼저 커밋해 이쪽 가입이 유니크에 걸리면") {
                fun racingService(uid: String): com.kbap.api.member.MemberService {
                    val racing = java.lang.reflect.Proxy.newProxyInstance(
                        com.kbap.common.domain.member.MemberJpaRepository::class.java.classLoader,
                        arrayOf(com.kbap.common.domain.member.MemberJpaRepository::class.java),
                    ) { _, method, args ->
                        val result = try {
                            method.invoke(memberRepository, *(args ?: emptyArray()))
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            throw e.targetException
                        }
                        if (method.name == "existsByProviderAndProviderUidAndMemberStatus") {
                            dataSource.connection.use { c ->
                                c.prepareStatement(
                                    "INSERT INTO member (provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) " +
                                        "VALUES ('GOOGLE', ?, 'ACTIVE', 0, 'ACTIVE', NOW(6), NOW(6))",
                                ).use { ps -> ps.setString(1, uid); ps.executeUpdate() }
                            }
                        }
                        result
                    } as com.kbap.common.domain.member.MemberJpaRepository
                    return com.kbap.api.member.MemberService(racing, uploadedImageService, orderRepository, "", "")
                }

                then("트랜잭션 밖이면 재조회가 그 커밋을 보고 기존 회원으로 로그인시킨다") {
                    val uid = "fresh-read-${System.nanoTime()}"

                    val (member, isNew) = racingService(uid).findOrSignUp(com.kbap.common.domain.member.model.SocialIdentity(SocialProvider.GOOGLE, uid, null))

                    isNew shouldBe false
                    member.id shouldBe memberIdOf(uid)
                }

                then("한 트랜잭션으로 묶으면 실패한다 — 유니크 위반이 세션을 무효화해 재조회가 깨진다") {
                    val uid = "one-tx-${System.nanoTime()}"

                    val outcome = runCatching {
                        org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                            racingService(uid).findOrSignUp(com.kbap.common.domain.member.model.SocialIdentity(SocialProvider.GOOGLE, uid, null))
                        }
                    }

                    val causes = generateSequence(outcome.exceptionOrNull()) { it.cause }.map { it.javaClass.name }.toList()
                    (
                        "org.hibernate.AssertionFailure" in causes ||
                            "org.springframework.transaction.UnexpectedRollbackException" in causes
                        ) shouldBe true
                }

                then("그래서 로그인·가입 경로에는 선언된 @Transactional 이 없다(메타 애너테이션 포함) — 호출자가 여는 트랜잭션은 이 검사 밖이다") {
                    val transactional = org.springframework.transaction.annotation.Transactional::class.java
                    val declared = { element: java.lang.reflect.AnnotatedElement ->
                        org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(element, transactional)
                    }
                    declared(com.kbap.api.member.MemberService::class.java.getMethod("findOrSignUp", com.kbap.common.domain.member.model.SocialIdentity::class.java)) shouldBe false
                    AuthService::class.java.methods.filter { it.name == "login" }.none(declared) shouldBe true
                    declared(com.kbap.api.member.MemberService::class.java) shouldBe false
                    declared(AuthService::class.java) shouldBe false
                }
            }

            `when`("정지되지 않은 회원은") {
                then("종전대로 로그인된다") {
                    loginAccessToken()

                    login().andReturn().response.status shouldBe 200
                }
            }
        }

        given("탈퇴 후 같은 소셜 계정 재가입") {
            `when`("탈퇴 직후 같은 소셜 계정으로 다시 로그인하면") {
                then("신규 회원으로 가입되고 이전 프로필을 승계하지 않으며, 새 토큰의 첫 회원 API 는 200 이다 — 탈퇴 회원 토큰이 다시 발급돼 MEMBER-003 이 반복되지 않는다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()
                    val previousId = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    withdraw(token).andReturn()

                    val response = login().andReturn().response

                    response.status shouldBe 200
                    response.contentAsString shouldContain "\"newMember\":true"
                    val newId = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    (newId != previousId) shouldBe true
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "onboarding_completed") shouldBe "0"
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "nickname") shouldBe null
                    getMyProfile(bodyToken(response, "accessToken")).andReturn().response.status shouldBe 200
                }
            }

            `when`("가입과 탈퇴를 두 번 반복한 뒤 다시 로그인하면") {
                then("삭제 표식이 회원마다 달라 유니크 충돌 없이 가입된다") {
                    withdraw(loginAccessToken()).andReturn()
                    withdraw(loginAccessToken()).andReturn()

                    val response = login().andReturn().response

                    response.status shouldBe 200
                    response.contentAsString shouldContain "\"newMember\":true"
                    getMyProfile(
                        objectMapper.readTree(response.contentAsString)
                            .path("payload").path("accessToken").asText(),
                    ).andReturn().response.status shouldBe 200
                }
            }
        }

        given("탈퇴한 회원이 들고 있던 refresh 토큰") {
            `when`("재발급하면") {
                then("401 로 거절된다") {
                    val loginResponse = login().andReturn().response
                    val refreshToken = bodyToken(loginResponse, "refreshToken")

                    withdraw(bodyToken(loginResponse, "accessToken")).andReturn().response.status shouldBe 200

                    refresh(refreshToken).andReturn().response.status shouldBe 401
                }
            }
        }
    }
}
