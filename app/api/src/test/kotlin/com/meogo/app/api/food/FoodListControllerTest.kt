package com.meogo.app.api.food
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class FoodListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedFoods(count: Int) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM food_avoidance_substance")
                    statement.execute("DELETE FROM food")
                    (1..count).forEach { id ->
                        statement.execute(
                            "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                                "name_translations, description_translations, status, created_at, updated_at) " +
                                "VALUES ($id, '목록메뉴$id', 'menu-$id.png', '목록메뉴$id 설명', 0, '{}', '{}', " +
                                "'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        )
                    }
                }
            }
        }

        fun seedLocalizedFood() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM food_avoidance_substance")
                    statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, status, created_at, updated_at) " +
                            "VALUES (500, '김치찌개', 'kimchi.png', '김치찌개 설명', 4, " +
                            "'{\"en\":\"Kimchi Stew\"}', '{}', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                }
            }
        }

        fun foodIdsOf(json: String): List<Long> =
            mapper.readTree(json).path("payload").path("items").map { it.path("foodId").asLong() }

        given("메뉴 목록 조회 API — 무한 스크롤 keyset 페이지네이션") {
            `when`("커서 없이 첫 페이지를 조회하면") {
                then("200 과 함께 최신순 20개·hasNext·nextCursor 를 BaseResponse 봉투로 반환한다") {
                    seedFoods(25)

                    mockMvc.get("/api/v1/foods").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.hasNext") { value(true) }
                        jsonPath("$.payload.nextCursor") { isNumber() }
                    }
                }
            }

            `when`("첫 페이지 nextCursor 로 다음 페이지를 이어 조회하면") {
                then("두 페이지 사이 foodId 가 겹치지 않는다") {
                    seedFoods(25)

                    val firstJson = mockMvc.get("/api/v1/foods")
                        .andReturn().response.getContentAsString(Charsets.UTF_8)
                    val firstIds = foodIdsOf(firstJson)
                    val nextCursor = mapper.readTree(firstJson).path("payload").path("nextCursor").asLong()

                    val secondJson = mockMvc.get("/api/v1/foods") {
                        param("cursor", nextCursor.toString())
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val secondIds = foodIdsOf(secondJson)

                    firstIds shouldHaveSize 20
                    secondIds.size shouldBeGreaterThan 0
                    (firstIds intersect secondIds.toSet()) shouldBe emptySet()
                }
            }
        }

        given("메뉴 목록 조회 API — 리치 카드(표시명 지역화·항목 필드 계약)") {
            `when`("lang=en 으로 조회하면 (en 번역 보유 food)") {
                then("항목 표시명이 영어로 지역화된다") {
                    seedLocalizedFood()

                    mockMvc.get("/api/v1/foods") {
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].name") { value("Kimchi Stew") }
                    }
                }
            }

            `when`("lang 미지정으로 조회하면") {
                then("항목 표시명이 한국어로 폴백된다") {
                    seedLocalizedFood()

                    mockMvc.get("/api/v1/foods").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].name") { value("김치찌개") }
                    }
                }
            }

            `when`("항목을 조회하면") {
                then("foodId·imageRef·spiciness·overallRiskStatus 필드 계약을 만족한다") {
                    seedLocalizedFood()

                    val json = mockMvc.get("/api/v1/foods")
                        .andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("foodId").isNumber shouldBe true
                    item.path("foodId").asLong() shouldBe 500L
                    item.path("imageRef").asText() shouldBe "kimchi.png"
                    item.path("spiciness").isInt shouldBe true
                    item.path("spiciness").asInt() shouldBe 4
                    item.path("spiciness").asInt() shouldBeInRange 0..10
                    item.path("overallRiskStatus").asText() shouldBeIn
                        listOf("SAFE", "CAUTION", "DANGER", "UNKNOWN")
                }
            }
        }

        given("메뉴 목록 조회 API — 언어 무관 한국어 메뉴명(koreanName)") {
            `when`("lang=en 으로 조회하면(지역화명이 한국어와 다름)") {
                then("항목 koreanName 에 한국어 원문을 담는다") {
                    seedLocalizedFood()

                    mockMvc.get("/api/v1/foods") {
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].name") { value("Kimchi Stew") }
                        jsonPath("$.payload.items[0].koreanName") { value("김치찌개") }
                    }
                }
            }

            `when`("lang 미지정으로 조회하면(지역화명이 곧 한국어)") {
                then("항목 koreanName 은 응답에 명시적 null 로 존재한다") {
                    seedLocalizedFood()

                    val json = mockMvc.get("/api/v1/foods")
                        .andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("name").asText() shouldBe "김치찌개"
                    item.has("koreanName") shouldBe true
                    item.get("koreanName").isNull shouldBe true
                }
            }
        }

        given("메뉴 목록 조회 API — 경계·오류 (US3)") {
            `when`("결과가 0건인 커서(최소 id 이하)로 조회하면") {
                then("200 과 함께 빈 배열·hasNext=false·nextCursor=null 을 BaseResponse 봉투로 반환한다") {
                    seedFoods(3)

                    val json = mockMvc.get("/api/v1/foods") {
                        param("cursor", "1")
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

            `when`("비숫자 커서(cursor=abc)로 조회하면") {
                then("400 과 함께 success=false·message 를 BaseResponse 봉투로 반환한다") {
                    seedFoods(3)

                    mockMvc.get("/api/v1/foods") {
                        param("cursor", "abc")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { exists() }
                    }
                }
            }

            `when`("음수 커서(cursor=-1)로 조회하면") {
                then("400 과 함께 success=false·message 를 BaseResponse 봉투로 반환한다") {
                    seedFoods(3)

                    mockMvc.get("/api/v1/foods") {
                        param("cursor", "-1")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { exists() }
                    }
                }
            }

            `when`("지원 목록에 없는 언어 코드(lang=xx)로 조회하면") {
                then("400 과 함께 success=false·지원 언어 안내 message 를 BaseResponse 봉투로 반환한다") {
                    seedFoods(3)

                    mockMvc.get("/api/v1/foods") {
                        param("lang", "xx")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { exists() }
                    }
                }
            }
        }
    }
}
