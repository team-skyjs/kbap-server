package com.kbap.api.place

import com.kbap.api.IntegrationTest
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.place.FoundPlace
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import javax.sql.DataSource

@IntegrationTest
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
            lang: String? = "en",
        ): ResultActionsDsl =
            mockMvc.get("/api/places/nearby") {
                token?.let { header("Authorization", "Bearer $it") }
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
                lang?.let { param("lang", it) }
            }

        fun search(
            token: String?,
            query: String? = "마리김밥",
            latitude: String? = "37.4979502",
            longitude: String? = "127.0276368",
            lang: String? = "en",
            page: Int? = null,
        ): ResultActionsDsl =
            mockMvc.get("/api/places/search") {
                token?.let { header("Authorization", "Bearer $it") }
                query?.let { param("query", it) }
                latitude?.let { param("latitude", it) }
                longitude?.let { param("longitude", it) }
                lang?.let { param("lang", it) }
                page?.let { param("page", it.toString()) }
            }

        val foundPlace = FoundPlace(
            placeId = "ChIJgangnam001",
            name = "한밥집 강남점",
            address = "서울 강남구 테헤란로 123",
            latitude = BigDecimal("37.4979502"),
            longitude = BigDecimal("127.0276368"),
        )

        given("주변 식당 조회 API — GET /api/places/nearby") {
            `when`("위도·경도·lang 으로 조회하면") {
                then("요청 언어가 seam 에 전달되고 주변 식당 목록을 반환한다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(foundPlace)

                    nearby(accessToken(800L), lang = "vi").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].placeId") { value("ChIJgangnam001") }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.items[0].address") { value("서울 강남구 테헤란로 123") }
                        jsonPath("$.payload.items[0].latitude") { value(37.4979502) }
                        jsonPath("$.payload.items[0].longitude") { value(127.0276368) }
                        jsonPath("$.payload.hasNext") { doesNotExist() }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe RecordedSearch(
                        query = null,
                        longitude = BigDecimal("127.0276368"),
                        latitude = BigDecimal("37.4979502"),
                        lang = LanguageCode.VI,
                    )
                }
            }

            `when`("지원 목록에 없는 lang 으로 조회하면") {
                then("en 으로 폴백해 전달한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(801L), lang = "fr").andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last().lang shouldBe LanguageCode.EN
                }
            }

            `when`("lang 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(802L), lang = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("결과가 없으면") {
                then("빈 목록을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(803L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(0) }
                    }
                }
            }

            `when`("latitude 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(804L), latitude = null).andExpect { status { isBadRequest() } }
                }
            }

            `when`("longitude 가 범위를 벗어나면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    nearby(accessToken(805L), longitude = "-180.1").andExpect { status { isBadRequest() } }
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

                    nearby(accessToken(806L)).andExpect {
                        status { isBadGateway() }
                        jsonPath("$.code") { value("PLACE-001") }
                    }
                }
            }
        }

        given("식당 키워드 검색 API — GET /api/places/search") {
            `when`("키워드·위도·경도·lang 으로 검색하면") {
                then("단일 목록을 반환하고 hasNext 는 존재하지 않는다") {
                    fakePlaceSearchClient.reset()
                    fakePlaceSearchClient.returns(foundPlace)

                    search(accessToken(810L), lang = "ja").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("한밥집 강남점") }
                        jsonPath("$.payload.hasNext") { doesNotExist() }
                    }
                    fakePlaceSearchClient.requests.last() shouldBe RecordedSearch(
                        query = "마리김밥",
                        longitude = BigDecimal("127.0276368"),
                        latitude = BigDecimal("37.4979502"),
                        lang = LanguageCode.JA,
                    )
                }
            }

            `when`("구 page 파라미터를 함께 보내면") {
                then("400 없이 무시된다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(811L), page = 3).andExpect { status { isOk() } }

                    fakePlaceSearchClient.requests.last().query shouldBe "마리김밥"
                }
            }

            `when`("lang 파라미터가 없으면") {
                then("400 을 반환한다") {
                    fakePlaceSearchClient.reset()

                    search(accessToken(812L), lang = null).andExpect { status { isBadRequest() } }
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
