package com.kbap.app.api.bookmark

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.application.auth.token.TokenIssuer
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class BookmarkControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/v1/bookmarks"

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, '{}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "bookmark-test-$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, nameTranslations: String = "{}", imageRef: String? = null): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, image_ref, description, spiciness,
                                      name_translations, description_translations, avoidance_substances, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, ?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY', name_translations = VALUES(name_translations),
                                            image_ref = VALUES(image_ref)
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.setString(3, imageRef)
                    ps.setString(4, nameTranslations)
                    ps.executeUpdate()
                }
            }

        fun registerBody(foodId: Long) = mapper.writeValueAsString(mapOf("foodId" to foodId))

        fun register(token: String, foodId: Long) =
            mockMvc.post(path) {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = registerBody(foodId)
            }

        fun listJson(token: String, lang: String? = "ko", cursor: Long? = null): String =
            mockMvc.get(path) {
                header("Authorization", "Bearer $token")
                lang?.let { param("lang", it) }
                cursor?.let { param("cursor", it.toString()) }
            }.andReturn().response.getContentAsString(Charsets.UTF_8)

        fun foodIdsOf(json: String): List<Long> =
            mapper.readTree(json).path("payload").path("items").map { it.path("foodId").asLong() }

        afterSpec {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM bookmark") } }
        }

        given("음식 북마크 등록 API — POST /api/v1/bookmarks") {
            `when`("유효한 foodId 로 등록하면") {
                then("200 과 success=true 를 반환하고 목록에 담긴다") {
                    val token = accessToken(200L)
                    seedFood(1L, "김치찌개")

                    register(token, 1L).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    foodIdsOf(listJson(token)) shouldContainExactlyInAnyOrder listOf(1L)
                }
            }

            `when`("같은 음식을 중복 등록하면") {
                then("둘 다 200 이고 목록에는 1건만 남는다(멱등)") {
                    val token = accessToken(201L)
                    seedFood(1L, "김치찌개")

                    register(token, 1L).andExpect { status { isOk() } }
                    register(token, 1L).andExpect { status { isOk() } }

                    foodIdsOf(listJson(token)) shouldContainExactlyInAnyOrder listOf(1L)
                }
            }

            `when`("미존재 foodId 로 등록하면") {
                then("400 과 success=false·code=FOOD-001 을 반환한다") {
                    val token = accessToken(202L)

                    register(token, 99999L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("액세스 토큰 없이 등록하면") {
                then("401 을 반환한다") {
                    seedFood(1L, "김치찌개")

                    mockMvc.post(path) {
                        contentType = MediaType.APPLICATION_JSON
                        content = registerBody(1L)
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }

        given("음식 북마크 취소 API — PATCH /api/v1/bookmarks/{foodId}") {
            `when`("등록한 음식을 취소하면") {
                then("200 과 success=true 를 반환하고 목록에서 사라진다") {
                    val token = accessToken(210L)
                    seedFood(1L, "김치찌개")
                    register(token, 1L).andExpect { status { isOk() } }

                    mockMvc.patch("$path/1") {
                        header("Authorization", "Bearer $token")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    foodIdsOf(listJson(token)) shouldBe emptyList<Long>()
                }
            }

            `when`("취소한 음식을 다시 등록하면") {
                then("목록에 다시 담긴다") {
                    val token = accessToken(211L)
                    seedFood(1L, "김치찌개")
                    register(token, 1L).andExpect { status { isOk() } }
                    mockMvc.patch("$path/1") {
                        header("Authorization", "Bearer $token")
                    }.andExpect { status { isOk() } }

                    register(token, 1L).andExpect { status { isOk() } }

                    foodIdsOf(listJson(token)) shouldContainExactlyInAnyOrder listOf(1L)
                }
            }

            `when`("액세스 토큰 없이 취소하면") {
                then("401 을 반환한다") {
                    mockMvc.patch("$path/1").andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }

        given("음식 북마크 목록 API — GET /api/v1/bookmarks") {
            `when`("여러 음식을 순서대로 등록하고 목록을 조회하면") {
                then("최근 등록한 음식이 맨 앞에 오는 최신순으로 반환한다") {
                    val token = accessToken(220L)
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "된장찌개")
                    seedFood(3L, "비빔밥")
                    register(token, 1L).andExpect { status { isOk() } }
                    register(token, 2L).andExpect { status { isOk() } }
                    register(token, 3L).andExpect { status { isOk() } }

                    val ids = foodIdsOf(listJson(token))

                    ids.firstOrNull() shouldBe 3L
                    ids shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
                }
            }

            `when`("lang=en 으로 조회하면(en 번역 보유 음식)") {
                then("항목 표시명이 영어로 지역화된다") {
                    val token = accessToken(221L)
                    seedFood(90010L, "북마크lang테스트-김치찌개", nameTranslations = """{"en":"Kimchi Stew"}""")
                    register(token, 90010L).andExpect { status { isOk() } }

                    listJson(token, lang = "en").let { json ->
                        mapper.readTree(json).path("payload").path("items").path(0).path("name").asText() shouldBe "Kimchi Stew"
                    }
                }
            }

            `when`("이미지 경로가 있는 음식을 북마크하고 조회하면") {
                then("항목 imageRef 는 CDN 도메인이 조합된 완전한 URL 이다") {
                    val token = accessToken(223L)
                    seedFood(90020L, "북마크이미지테스트-된장찌개", imageRef = "bookmark/doenjang.png")
                    register(token, 90020L).andExpect { status { isOk() } }

                    listJson(token).let { json ->
                        mapper.readTree(json).path("payload").path("items").path(0).path("imageRef").asText() shouldBe
                            "https://cdn.test/bookmark/doenjang.png"
                    }
                }
            }

            `when`("PAGE_SIZE(20)를 초과해 등록하면") {
                then("첫 페이지는 20개·hasNext·nextCursor 를 주고 다음 페이지가 나머지를 이어 준다") {
                    val token = accessToken(222L)
                    val foodIds = (1L..21L).toList()
                    foodIds.forEach { id ->
                        seedFood(id, "메뉴$id")
                        register(token, id).andExpect { status { isOk() } }
                    }

                    val firstJson = listJson(token)
                    val firstRoot = mapper.readTree(firstJson)
                    firstRoot.path("payload").path("items").size() shouldBe 20
                    firstRoot.path("payload").path("hasNext").asBoolean() shouldBe true
                    firstRoot.path("payload").path("nextCursor").isNumber shouldBe true

                    val nextCursor = firstRoot.path("payload").path("nextCursor").asLong()
                    val secondJson = listJson(token, cursor = nextCursor)
                    val secondRoot = mapper.readTree(secondJson)
                    secondRoot.path("payload").path("items").size() shouldBe 1
                    secondRoot.path("payload").path("hasNext").asBoolean() shouldBe false

                    (foodIdsOf(firstJson) + foodIdsOf(secondJson)) shouldContainExactlyInAnyOrder foodIds
                }
            }

            `when`("목록 항목의 북마크 여부(bookmarked)를 확인하면") {
                then("정의상 전부 북마크한 음식이므로 모든 항목이 bookmarked=true 다") {
                    val token = accessToken(230L)
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "된장찌개")
                    register(token, 1L).andExpect { status { isOk() } }
                    register(token, 2L).andExpect { status { isOk() } }

                    val items = mapper.readTree(listJson(token)).path("payload").path("items").toList()

                    items.size shouldBe 2
                    items.forEach { it.path("bookmarked").asBoolean() shouldBe true }
                }
            }

            `when`("액세스 토큰 없이 목록을 조회하면") {
                then("401 을 반환한다") {
                    mockMvc.get("$path?lang=ko").andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }
    }
}
