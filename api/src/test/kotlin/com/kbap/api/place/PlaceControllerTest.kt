package com.kbap.api.place

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.place.FoundPlace
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, FakePlaceSearchConfig::class)
class PlaceControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var fakePlaceSearchClient: FakePlaceSearchClient

    init {
        val path = "/api/v1/places"

        fun accessToken(memberId: Long): String {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, '{"countryCode":"KR"}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE nickname = VALUES(nickname)
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "place-test-$memberId")
                    ps.setString(3, "장소검색$memberId")
                    ps.executeUpdate()
                }
            }
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun search(token: String?, query: String?, page: Int? = null): ResultActionsDsl =
            mockMvc.get(path) {
                token?.let { header("Authorization", "Bearer $it") }
                query?.let { param("query", it) }
                page?.let { param("page", it.toString()) }
            }

        given("장소 검색 API — GET /api/v1/places") {
            `when`("키워드로 검색하면") {
                then("검색 결과 목록을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(
                        FoundPlace(
                            name = "한밥집 강남점",
                            address = "서울 강남구 테헤란로 123",
                            kakaoPlaceId = "27290047",
                            latitude = BigDecimal("37.4979502"),
                            longitude = BigDecimal("127.0276368"),
                        ),
                        hasNext = true,
                    )

                    search(accessToken(800L), "한밥집").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.items[0].address") { value("서울 강남구 테헤란로 123") }
                        jsonPath("$.payload.items[0].kakaoPlaceId") { value("27290047") }
                        jsonPath("$.payload.items[0].latitude") { value(37.4979502) }
                        jsonPath("$.payload.items[0].longitude") { value(127.0276368) }
                        jsonPath("$.payload.hasNext") { value(true) }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe ("한밥집" to 1)
                }
            }

            `when`("page 를 지정하면") {
                then("해당 페이지로 검색한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(801L), "한밥집", page = 3).andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last() shouldBe ("한밥집" to 3)
                }
            }

            `when`("검색 결과가 없으면") {
                then("빈 목록을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(802L), "없는가게이름").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(0) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("키워드가 공백이면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(803L), "   ").andExpect { status { isBadRequest() } }
                }
            }

            `when`("키워드 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(804L), null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(null, "한밥집").andExpect { status { isUnauthorized() } }
                }
            }

            `when`("외부 장소 검색이 실패하면") {
                then("502 와 PLACE-001 을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.failure = BusinessException(ErrorCode.PLACE_SEARCH_FAILED)

                    search(accessToken(805L), "한밥집").andExpect {
                        status { isBadGateway() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("PLACE-001") }
                    }
                }
            }
        }
    }
}
