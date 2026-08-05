package com.kbap.api.food

import com.kbap.common.core.error.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
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
class FoodSearchControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedSearchableFoods() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_ranking_event")
                    statement.execute("DELETE FROM food_review")
                    statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                            "VALUES (601, '김치찌개', '김치찌개', 'kimchi.png', '김치찌개 설명', 4, " +
                            "'{\"en\":\"Kimchi Stew\"}', '{}', '[]', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                            "VALUES (602, '김치볶음밥', '김치볶음밥', 'kimchi-rice.png', '김치볶음밥 설명', 3, " +
                            "'{\"en\":\"Kimchi Fried Rice\"}', '{}', '[]', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                            "VALUES (603, '된장찌개', '된장찌개', 'doenjang.png', '된장찌개 설명', 0, " +
                            "'{\"en\":\"Doenjang Stew\"}', '{}', '[]', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                }
            }
        }

        fun seedNumberedFoods(count: Int) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_ranking_event")
                    statement.execute("DELETE FROM food_review")
                    statement.execute("DELETE FROM food")
                    (1..count).forEach { index ->
                        statement.execute(
                            "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                                "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                                "VALUES (${700 + index}, '검색메뉴$index', '검색메뉴$index', 'menu-$index.png', '검색메뉴$index 설명', 0, " +
                                "'{}', '{}', '[]', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        )
                    }
                }
            }
        }

        fun seedJapaneseOnlyFood() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_ranking_event")
                    statement.execute("DELETE FROM food_review")
                    statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                            "VALUES (610, '냉면', '냉면', 'naengmyeon.png', '냉면 설명', 0, " +
                            "'{\"ja\":\"レイメン\",\"en\":\"Cold Noodles\"}', '{}', '[]', " +
                            "'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                }
            }
        }

        fun foodIdsOf(json: String): List<Long> =
            mapper.readTree(json).path("payload").path("items").map { it.path("foodId").asLong() }

        fun messageOf(json: String): String = mapper.readTree(json).path("message").asText()

        fun seedBookmarkRow(memberId: Long, foodId: Long) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO member (id, provider, provider_uid, profile, member_status, " +
                            "onboarding_completed, status, created_at, updated_at) " +
                            "VALUES ($memberId, 'GOOGLE', 'food-bm-$memberId', '{}', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) " +
                            "ON DUPLICATE KEY UPDATE id = id",
                    )
                    statement.execute(
                        "INSERT INTO bookmark (member_id, food_id, status, created_at, updated_at) " +
                            "VALUES ($memberId, $foodId, 'ACTIVE', NOW(6), NOW(6))",
                    )
                }
            }
        }

        beforeTest {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM bookmark") } }
        }

        given("메뉴 검색 API — 북마크 여부(bookmarked)") {
            `when`("회원이 검색 결과 중 일부를 북마크한 상태로 검색하면") {
                then("북마크한 항목만 bookmarked=true, 나머지는 false 다") {
                    seedSearchableFoods()
                    seedBookmarkRow(400L, 601L)
                    val token = tokenIssuer.issueAccessToken(400L, MemberRole.USER)

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "김치")
                        header("Authorization", "Bearer $token")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val byId = mapper.readTree(json).path("payload").path("items").toList()
                        .associate { it.path("foodId").asLong() to it.path("bookmarked").asBoolean() }

                    byId[601L] shouldBe true
                    byId[602L] shouldBe false
                }
            }

            `when`("비회원이 검색하면") {
                then("전 항목 bookmarked=false 이고 필드는 항상 존재한다") {
                    seedSearchableFoods()
                    seedBookmarkRow(401L, 601L)

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "김치")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val items = mapper.readTree(json).path("payload").path("items").toList()

                    items.forEach { item ->
                        item.has("bookmarked") shouldBe true
                        item.path("bookmarked").asBoolean() shouldBe false
                    }
                }
            }
        }

        fun seedAvoidanceSubstance(foodId: Long, substanceCode: String, inclusionPercent: Int) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT IGNORE INTO avoidance_substance " +
                            "(code, korean_name, translations, status, created_at, updated_at) " +
                            "VALUES ('$substanceCode', '$substanceCode', '{}', " +
                            "'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "UPDATE food SET avoidance_substances = JSON_ARRAY_APPEND(avoidance_substances, '$', " +
                            "JSON_OBJECT('code', '$substanceCode', 'inclusion_percent', $inclusionPercent)) " +
                            "WHERE id = $foodId",
                    )
                }
            }
        }

        given("메뉴 검색 API — 검색어 부분 일치") {
            `when`("한국어명 조각(keyword=김치)으로 검색하면") {
                then("200 과 함께 매칭 메뉴만 BaseResponse 봉투로 반환한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
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
                    seedSearchableFoods()

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
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
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

        given("메뉴 검색 API — 항목 스키마 계약 (FR-009)") {
            `when`("검색 결과 항목을 조회하면") {
                then("foodId·koreanName·imageRef·spiciness·overallRiskStatus 필드 계약을 만족한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "김치찌개")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("foodId").isNumber shouldBe true
                    item.path("foodId").asLong() shouldBe 601L
                    item.get("koreanName").isNull shouldBe true
                    item.path("imageRef").asText() shouldBe "https://cdn.test/kimchi.png"
                    item.path("spiciness").asInt() shouldBe 4
                    item.path("overallRiskStatus").asText() shouldBe "SAFE"
                }
            }
        }

        given("메뉴 검색 API — 회피 성분 종합 위험도 실 스택 계산 (FR-009)") {
            `when`("SOY 를 회피하는 회원이 SOY 를 100% 포함하는 메뉴를 검색하면") {
                then("실 스택(프로필 조회 → 성분 fetch → 카탈로그 조회 → 위험도 산출)이 DANGER 를 계산해 내려준다") {
                    seedSearchableFoods()
                    seedAvoidanceSubstance(foodId = 601L, substanceCode = "SOY", inclusionPercent = 100)
                    FoodTestSeed.seedMemberAvoiding(dataSource, 11L, "SOY")
                    val token = tokenIssuer.issueAccessToken(11L, MemberRole.USER)

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "김치찌개")
                        header("Authorization", "Bearer $token")
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("foodId").asLong() shouldBe 601L
                    item.path("overallRiskStatus").asText() shouldBe "DANGER"
                }
            }

            `when`("성분이 없는 메뉴를 검색하면") {
                then("위험도는 SAFE 다") {
                    seedSearchableFoods()
                    seedAvoidanceSubstance(foodId = 601L, substanceCode = "SOY", inclusionPercent = 100)

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "된장찌개")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("foodId").asLong() shouldBe 603L
                    item.path("overallRiskStatus").asText() shouldBe "SAFE"
                }
            }
        }

        given("메뉴 검색 API — 표시명 지역화·koreanName (FR-010)") {
            `when`("lang=en 으로 검색하면 (en 번역 보유 메뉴)") {
                then("name 은 영어 번역명이고 koreanName 에 한국어 원문을 담는다") {
                    seedSearchableFoods()

                    mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "kimchi stew")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].name") { value("Kimchi Stew") }
                        jsonPath("$.payload.items[0].koreanName") { value("김치찌개") }
                    }
                }
            }

            `when`("lang 미지정으로 검색하면 (표시명이 곧 한국어)") {
                then("koreanName 은 응답에 명시적 null 로 존재한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "김치찌개")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("name").asText() shouldBe "김치찌개"
                    item.get("koreanName").isNull shouldBe true
                }
            }
        }

        given("메뉴 검색 API — 표시명 띄어쓰기와 무관한 매칭 (KB-298)") {
            fun seedSpacedFood() {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("DELETE FROM member_ranking_event")
                        statement.execute("DELETE FROM food_review")
                        statement.execute("DELETE FROM food")
                        statement.execute(
                            "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                                "name_translations, description_translations, avoidance_substances, status, created_at, updated_at) " +
                                "VALUES (620, '들깨칼국수', '들깨 칼국수', 'kalguksu.png', '들깨 칼국수 설명', 0, " +
                                "'{}', '{}', '[]', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        )
                    }
                }
            }

            `when`("화면 표기 그대로(공백 포함) 검색하면") {
                then("표시명을 그대로 담은 결과를 돌려준다") {
                    seedSpacedFood()

                    mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "들깨 칼국수")
                        param("lang", "ko")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("들깨 칼국수") }
                    }
                }
            }

            `when`("표시명에만 있는 영문 조각으로 검색하면") {
                then("match key 에서 지워진 조각이어도 표시명으로 찾는다") {
                    seedSpacedFood()

                    mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "칼국수")
                        param("lang", "ko")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].name") { value("들깨 칼국수") }
                    }
                }
            }

        }

        given("메뉴 검색 API — 언어 분리 (불변식 2·3)") {
            `when`("일본어 번역명에만 있는 키워드를 lang=ja 로 검색하면") {
                then("해당 메뉴가 결과에 포함된다") {
                    seedJapaneseOnlyFood()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "レイメン")
                        param("lang", "ja")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    foodIdsOf(json) shouldBe listOf(610L)
                }
            }

            `when`("같은 키워드를 lang=en 으로 검색하면") {
                then("요청 언어가 아니므로 결과에 포함되지 않는다 (불변식 2)") {
                    seedJapaneseOnlyFood()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "レイメン")
                        param("lang", "en")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    foodIdsOf(json) shouldBe emptyList()
                }
            }

            `when`("번역명에만 있는 키워드를 lang 미지정으로 검색하면") {
                then("ko 폴백이라 한국어명만 매칭해 결과에 포함되지 않는다 (불변식 3)") {
                    seedJapaneseOnlyFood()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "Cold Noodles")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    foodIdsOf(json) shouldBe emptyList()
                }
            }
        }

        given("메뉴 검색 API — 지원 목록 밖 언어 코드는 영어로 폴백 (FR-004)") {
            `when`("존재하지 않는 언어 코드(lang=fr)로 검색하면") {
                then("400 이 아니라 영어 표시명으로 응답한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "김치")
                        param("lang", "fr")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    mapper.readTree(json).path("payload").path("items")
                        .map { it.path("name").asText() } shouldContain "Kimchi Stew"
                }
            }

            `when`("대소문자가 다른 언어 코드(lang=EN)로 검색하면") {
                then("정확 일치가 아니므로 영어로 폴백한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search") {
                        param("keyword", "김치")
                        param("lang", "EN")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    mapper.readTree(json).path("payload").path("items")
                        .map { it.path("name").asText() } shouldContain "Kimchi Stew"
                }
            }
        }

        given("메뉴 검색 API — 커서 연속성 (US2)") {
            `when`("같은 검색어로 첫 페이지를 조회하면") {
                then("최신순 20개·hasNext=true·nextCursor 를 반환한다") {
                    seedNumberedFoods(25)

                    mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(20) }
                        jsonPath("$.payload.hasNext") { value(true) }
                        jsonPath("$.payload.nextCursor") { isNumber() }
                    }
                }
            }

            `when`("첫 페이지 nextCursor 를 같은 검색어와 함께 넘겨 다음 페이지를 조회하면") {
                then("두 페이지의 foodId 교집합이 공집합이고 단조 감소한다") {
                    seedNumberedFoods(25)

                    val firstJson = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val firstIds = foodIdsOf(firstJson)
                    val nextCursor = mapper.readTree(firstJson).path("payload").path("nextCursor").asLong()

                    val secondJson = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                        param("cursor", nextCursor.toString())
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val secondIds = foodIdsOf(secondJson)

                    firstIds shouldHaveSize 20
                    secondIds.size shouldBeGreaterThan 0
                    (firstIds intersect secondIds.toSet()) shouldBe emptySet()
                    secondIds.max() shouldBeLessThan firstIds.min()
                }
            }

            `when`("마지막 페이지를 조회하면") {
                then("남은 항목과 함께 hasNext=false·nextCursor=null 을 반환한다") {
                    seedNumberedFoods(25)

                    val firstJson = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val nextCursor = mapper.readTree(firstJson).path("payload").path("nextCursor").asLong()

                    val lastJson = mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                        param("cursor", nextCursor.toString())
                    }.andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val payload = mapper.readTree(lastJson).path("payload")

                    payload.path("items").size() shouldBe 5
                    payload.path("hasNext").asBoolean() shouldBe false
                    payload.path("nextCursor").isNull shouldBe true
                }
            }
        }

        given("메뉴 검색 API — 잘못된 커서 (FR-014)") {
            `when`("비숫자 커서(cursor=abc)로 검색하면") {
                then("400 과 함께 success=false·커서 형식 안내 message 를 반환한다") {
                    seedNumberedFoods(3)

                    mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                        param("cursor", "abc")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("커서 형식이 올바르지 않습니다") }
                    }
                }
            }

            `when`("음수 커서(cursor=-1)로 검색하면") {
                then("400 과 함께 success=false·커서 형식 안내 message 를 반환한다") {
                    seedNumberedFoods(3)

                    mockMvc.get("/api/v1/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                        param("cursor", "-1")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("커서 형식이 올바르지 않습니다") }
                    }
                }
            }
        }

        given("메뉴 검색 API — 검색어의 패턴 특수문자는 리터럴 (FR-003a)") {
            `when`("keyword=% 로 검색하면") {
                then("전체 메뉴가 쏟아지지 않고 매칭 0건이면 빈 목록 200 을 반환한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/v1/foods/search?lang=ko") {
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
                    seedSearchableFoods()

                    mockMvc.get("/api/v1/foods/search?lang=ko") {
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
                    seedSearchableFoods()

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
                    seedSearchableFoods()

                    mockMvc.get("/api/v1/foods/search?lang=ko") {
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
