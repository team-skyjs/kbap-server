package com.kbap.api.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/admin/foods"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("DELETE FROM food_content_outbox"); it.execute("DELETE FROM food") }
            }

        fun seedExistingFood(koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeUpdate()
                }
            }

        fun contentStatusOf(koreanName: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT content_status FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun countFoods(): Long =
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeQuery("SELECT COUNT(*) FROM food").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        fun adminToken(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun seedBody(names: List<String>?): String = mapper.writeValueAsString(mapOf("koreanNames" to names))

        fun postSeed(body: String, token: String? = adminToken()): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        beforeContainer { clearFoods() }

        given("관리자 신규 음식 적재 — 정상 흐름") {
            `when`("신규·기존·중복·공백이 섞인 목록을 제출하면") {
                then("정제 후 신규만 FAILED 로 생성되고 카운트를 돌려준다") {
                    seedExistingFood("기존비빔밥")

                    postSeed(seedBody(listOf("신규마라샹궈", "기존비빔밥", " 신규마라샹궈 ", "신규탕후루", "  ")))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.success") { value(true) }
                            jsonPath("$.payload.requested") { value(3) }
                            jsonPath("$.payload.created") { value(2) }
                            jsonPath("$.payload.skipped") { value(1) }
                        }

                    countFoods() shouldBe 3
                    contentStatusOf("신규마라샹궈") shouldBe "FAILED"
                    contentStatusOf("신규탕후루") shouldBe "FAILED"
                    contentStatusOf("기존비빔밥") shouldBe "READY"
                }
            }

            `when`("공백·비한글이 섞인 표기로 제출하면") {
                then("스캔 입구와 동일하게 정규화(NFC·한글만)해 저장·중복 판정한다") {
                    postSeed(seedBody(listOf("김치 찌개", "김치찌개", "Kimchi 김치찌개!", "abc123")))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.requested") { value(1) }
                            jsonPath("$.payload.created") { value(1) }
                            jsonPath("$.payload.skipped") { value(0) }
                        }

                    countFoods() shouldBe 1
                    contentStatusOf("김치찌개") shouldBe "FAILED"
                }
            }

            `when`("적재된 음식의 초기 조사 상태를 보면") {
                then("맵기 -1, 기피성분 NULL(미조사) 로 저장돼 있다") {
                    postSeed(seedBody(listOf("센티널마라탕"))).andExpect { status { isOk() } }

                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            "SELECT spiciness, ingredients FROM food WHERE korean_name = ?",
                        ).use { ps ->
                            ps.setString(1, "센티널마라탕")
                            ps.executeQuery().use { rs ->
                                rs.next() shouldBe true
                                rs.getInt(1) shouldBe -1
                                rs.getString(2) shouldBe null
                            }
                        }
                    }
                }
            }

            `when`("같은 목록을 다시 제출하면") {
                then("멱등하게 created=0 으로 성공하고 행이 늘지 않는다") {
                    val body = seedBody(listOf("재실행마라탕", "재실행분짜"))

                    postSeed(body).andExpect { status { isOk() } }
                    postSeed(body)
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.requested") { value(2) }
                            jsonPath("$.payload.created") { value(0) }
                            jsonPath("$.payload.skipped") { value(2) }
                        }

                    countFoods() shouldBe 2
                }
            }

            `when`("빈 배열이나 공백뿐인 목록을 제출하면") {
                then("0건 생성으로 성공 처리한다") {
                    postSeed(seedBody(listOf("   ", "")))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.requested") { value(0) }
                            jsonPath("$.payload.created") { value(0) }
                            jsonPath("$.payload.skipped") { value(0) }
                        }

                    countFoods() shouldBe 0
                }
            }
        }

        given("관리자 신규 음식 적재 — 인가") {
            `when`("토큰 없이 호출하면") {
                then("401 로 거절하고 아무것도 생성하지 않는다") {
                    postSeed(seedBody(listOf("무단-마라탕")), token = null)
                        .andExpect { status { isUnauthorized() } }

                    countFoods() shouldBe 0
                }
            }

            `when`("위조 서명 토큰으로 호출하면") {
                then("401 로 거절한다") {
                    postSeed(seedBody(listOf("위조-마라탕")), token = "forged.token.value")
                        .andExpect { status { isUnauthorized() } }

                    countFoods() shouldBe 0
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("AUTH-008(403) 로 거절하고 아무것도 생성하지 않는다") {
                    postSeed(seedBody(listOf("일반유저-마라탕")), token = tokenIssuer.issueAccessToken(1, MemberRole.USER))
                        .andExpect {
                            status { isForbidden() }
                            jsonPath("$.success") { value(false) }
                            jsonPath("$.code") { value(ErrorCode.ADMIN_FORBIDDEN.code) }
                        }

                    countFoods() shouldBe 0
                }
            }

            `when`("ADMIN 역할 토큰으로 호출하면") {
                then("정상 적재된다") {
                    postSeed(seedBody(listOf("관리자마라탕")))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.created") { value(1) }
                        }

                    contentStatusOf("관리자마라탕") shouldBe "FAILED"
                }
            }
        }

        given("관리자 신규 음식 적재 — 요청 검증") {
            `when`("255자를 넘는 이름이 섞여 있으면") {
                then("COMMON-002 로 거절하고 아무것도 생성하지 않는다") {
                    postSeed(seedBody(listOf("정상-김치찌개", "가".repeat(256))))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.success") { value(false) }
                            jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                        }

                    countFoods() shouldBe 0
                }
            }

            `when`("500건을 넘는 목록을 제출하면") {
                then("COMMON-002 로 거절한다") {
                    postSeed(seedBody((1..501).map { "메뉴이름$it" }))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                        }

                    countFoods() shouldBe 0
                }
            }

            `when`("koreanNames 필드 없이 제출하면") {
                then("COMMON-002 로 거절한다") {
                    postSeed("{}")
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value(ErrorCode.INVALID_REQUEST.code) }
                        }
                }
            }
        }
    }
}
