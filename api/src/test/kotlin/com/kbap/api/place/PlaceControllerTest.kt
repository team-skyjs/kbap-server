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

        fun nearby(
            token: String?,
            latitude: String? = "37.4979502",
            longitude: String? = "127.0276368",
        ): ResultActionsDsl =
            mockMvc.get("/api/places/nearby") {
                token?.let { header("Authorization", "Bearer $it") }
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
            }

        fun search(
            token: String?,
            query: String? = "마리김밥",
            latitude: String? = "37.4979502",
            longitude: String? = "127.0276368",
            page: Int? = null,
        ): ResultActionsDsl =
            mockMvc.get("/api/places/search") {
                token?.let { header("Authorization", "Bearer $it") }
                query?.let { param("query", it) }
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
                page?.let { param("page", it.toString()) }
            }

        val foundPlace = FoundPlace(
            name = "한밥집 강남점",
            address = "서울 강남구 테헤란로 123",
            latitude = BigDecimal("37.4979502"),
            longitude = BigDecimal("127.0276368"),
        )

        given("주변 식당 탑10 API — GET /api/places/nearby") {
            `when`("위도·경도로 조회하면") {
                then("음식점 키워드 고정으로 주변 식당 목록을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(foundPlace)

                    nearby(accessToken(800L)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.items[0].address") { value("서울 강남구 테헤란로 123") }
                        jsonPath("$.payload.items[0].latitude") { value(37.4979502) }
                        jsonPath("$.payload.items[0].longitude") { value(127.0276368) }
                        jsonPath("$.payload.hasNext") { doesNotExist() }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe RecordedSearch(
                        query = PlaceSearchService.RESTAURANT_KEYWORD,
                        longitude = BigDecimal("127.0276368"),
                        latitude = BigDecimal("37.4979502"),
                        page = null,
                    )
                }
            }

            `when`("결과가 없으면") {
                then("빈 목록을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(801L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(0) }
                    }
                }
            }

            `when`("latitude 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(802L), latitude = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("longitude 가 범위를 벗어나면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(803L), longitude = "-180.1").andExpect { status { isBadRequest() } }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(null).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("외부 장소 검색이 실패하면") {
                then("502 와 PLACE-001 을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.failure = BusinessException(ErrorCode.PLACE_SEARCH_FAILED)

                    nearby(accessToken(804L)).andExpect {
                        status { isBadGateway() }
                        jsonPath("$.code") { value("PLACE-001") }
                    }
                }
            }
        }

        given("식당 키워드 검색 API — GET /api/places/search") {
            `when`("키워드와 위도·경도로 검색하면") {
                then("가까운 순 목록과 hasNext 를 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(foundPlace, hasNext = true)

                    search(accessToken(810L)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.hasNext") { value(true) }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe RecordedSearch(
                        query = "마리김밥",
                        longitude = BigDecimal("127.0276368"),
                        latitude = BigDecimal("37.4979502"),
                        page = 1,
                    )
                }
            }

            `when`("page 를 지정하면") {
                then("해당 페이지로 검색한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(811L), page = 3).andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last().page shouldBe 3
                }
            }

            `when`("page 가 범위를 벗어나면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(812L), page = 46).andExpect { status { isBadRequest() } }
                }
            }

            `when`("query 가 공백이면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(813L), query = "   ").andExpect { status { isBadRequest() } }
                }
            }

            `when`("query 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(814L), query = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("latitude 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(815L), latitude = null).andExpect { status { isBadRequest() } }
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

                    search(accessToken(816L)).andExpect {
                        status { isBadGateway() }
                        jsonPath("$.code") { value("PLACE-001") }
                    }
                }
            }
        }
    }
}
