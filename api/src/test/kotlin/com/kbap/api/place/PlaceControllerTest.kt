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
        val path = "/api/places"

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

        fun search(
            token: String?,
            latitude: String? = "37.4979502",
            longitude: String? = "127.0276368",
            query: String? = null,
        ): ResultActionsDsl =
            mockMvc.get(path) {
                token?.let { header("Authorization", "Bearer $it") }
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
                query?.let { param("query", it) }
            }

        given("주변 식당 검색 API — GET /api/places") {
            `when`("위도·경도로 검색하면") {
                then("음식점 키워드 고정으로 주변 식당 목록을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(
                        FoundPlace(
                            name = "한밥집 강남점",
                            address = "서울 강남구 테헤란로 123",
                            latitude = BigDecimal("37.4979502"),
                            longitude = BigDecimal("127.0276368"),
                        ),
                    )

                    search(accessToken(800L)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.items[0].address") { value("서울 강남구 테헤란로 123") }
                        jsonPath("$.payload.items[0].latitude") { value(37.4979502) }
                        jsonPath("$.payload.items[0].longitude") { value(127.0276368) }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe RecordedSearch(
                        query = PlaceSearchService.RESTAURANT_KEYWORD,
                        longitude = BigDecimal("127.0276368"),
                        latitude = BigDecimal("37.4979502"),
                    )
                }
            }

            `when`("query 를 지정하면") {
                then("해당 키워드로 검색한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(801L), query = "마리김밥").andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last().query shouldBe "마리김밥"
                }
            }

            `when`("query 가 공백이면") {
                then("기본 키워드(음식점)로 검색한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(808L), query = "   ").andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last().query shouldBe PlaceSearchService.RESTAURANT_KEYWORD
                }
            }

            `when`("검색 결과가 없으면") {
                then("빈 목록을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(802L)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(0) }
                    }
                }
            }

            `when`("latitude 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(803L), latitude = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("longitude 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(804L), longitude = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("latitude 가 범위를 벗어나면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(805L), latitude = "90.1").andExpect { status { isBadRequest() } }
                }
            }

            `when`("longitude 가 범위를 벗어나면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(806L), longitude = "-180.1").andExpect { status { isBadRequest() } }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(null).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("외부 장소 검색이 실패하면") {
                then("502 와 PLACE-001 을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.failure = BusinessException(ErrorCode.PLACE_SEARCH_FAILED)

                    search(accessToken(807L)).andExpect {
                        status { isBadGateway() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("PLACE-001") }
                    }
                }
            }
        }
    }
}
