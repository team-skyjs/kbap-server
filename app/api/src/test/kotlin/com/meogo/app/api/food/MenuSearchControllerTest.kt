package com.meogo.app.api.food

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class MenuSearchControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedSearchableMenus() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM food_avoidance_substance")
                    statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, status, created_at, updated_at) " +
                            "VALUES (601, '김치찌개', 'kimchi.png', '김치찌개 설명', 4, " +
                            "'{\"en\":\"Kimchi Stew\"}', '{}', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, status, created_at, updated_at) " +
                            "VALUES (602, '김치볶음밥', 'kimchi-rice.png', '김치볶음밥 설명', 3, " +
                            "'{\"en\":\"Kimchi Fried Rice\"}', '{}', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, status, created_at, updated_at) " +
                            "VALUES (603, '된장찌개', 'doenjang.png', '된장찌개 설명', 0, " +
                            "'{\"en\":\"Doenjang Stew\"}', '{}', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                }
            }
        }

        fun foodIdsOf(json: String): List<Long> =
            mapper.readTree(json).path("payload").path("items").map { it.path("foodId").asLong() }

        given("메뉴 검색 API — 검색어 부분 일치") {
            `when`("한국어명 조각(keyword=김치)으로 검색하면") {
                then("200 과 함께 매칭 메뉴만 BaseResponse 봉투로 반환한다") {
                    seedSearchableMenus()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "김치")
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val root = mapper.readTree(json)

                    root.path("success").asBoolean() shouldBe true
                    foodIdsOf(json) shouldBe listOf(602L, 601L)
                }
            }

            `when`("영어 번역명 조각(keyword=kimchi&lang=en)으로 검색하면") {
                then("대소문자 무관 번역명 매칭 메뉴를 반환한다") {
                    seedSearchableMenus()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "kimchi")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    foodIdsOf(json) shouldBe listOf(602L, 601L)
                }
            }

            `when`("어떤 메뉴에도 없는 검색어로 검색하면") {
                then("오류가 아니라 200 과 빈 배열·hasNext=false·nextCursor=null 을 반환한다") {
                    seedSearchableMenus()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "파스타")
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val root = mapper.readTree(json)

                    root.path("success").asBoolean() shouldBe true
                    root.path("payload").path("items").size() shouldBe 0
                    root.path("payload").path("hasNext").asBoolean() shouldBe false
                    root.path("payload").path("nextCursor").isNull shouldBe true
                }
            }
        }

        given("메뉴 검색 API — 검색어의 패턴 특수문자는 리터럴 (FR-003a)") {
            `when`("keyword=% 로 검색하면") {
                then("전체 메뉴가 쏟아지지 않고 매칭 0건이면 빈 목록 200 을 반환한다") {
                    seedSearchableMenus()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "%")
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val root = mapper.readTree(json)

                    root.path("success").asBoolean() shouldBe true
                    root.path("payload").path("items").size() shouldBe 0
                    root.path("payload").path("hasNext").asBoolean() shouldBe false
                }
            }
        }

        given("메뉴 검색 API — 빈/공백 검색어 (FR-011)") {
            `when`("빈 검색어(keyword=)로 검색하면") {
                then("400 과 함께 success=false·검색어 안내 message 를 BaseResponse 봉투로 반환한다") {
                    seedSearchableMenus()

                    mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.payload") { value(null) }
                        jsonPath("$.message") { value("검색어를 입력해 주세요") }
                    }
                }
            }

            `when`("keyword 파라미터를 아예 붙이지 않고 검색하면") {
                then("400 과 함께 success=false·빈 검색어와 동일한 안내 message 를 반환한다") {
                    seedSearchableMenus()

                    mockMvc.get("/api/v1/foods/search") {
                        param("lang", "en")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.payload") { value(null) }
                        jsonPath("$.message") { value("검색어를 입력해 주세요") }
                    }
                }
            }

            `when`("공백뿐인 검색어(keyword=   )로 검색하면") {
                then("400 과 함께 success=false·검색어 안내 message 를 BaseResponse 봉투로 반환한다") {
                    seedSearchableMenus()

                    mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "   ")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.payload") { value(null) }
                        jsonPath("$.message") { value("검색어를 입력해 주세요") }
                    }
                }
            }
        }
    }
}
