package com.kbap.api.food

import com.kbap.api.IntegrationTest
import com.kbap.common.core.error.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
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
                    statement.execute("DELETE FROM food_content_outbox")
                statement.execute("DELETE FROM food_vector_outbox")
                statement.execute("DELETE FROM food_image")
                statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                            "VALUES (601, '김치찌개', '김치찌개', 'kimchi.png', '김치찌개 설명', 4, " +
                            "'{\"en\":\"Kimchi Stew\"}', '{}', '[]', 'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                            "VALUES (602, '김치볶음밥', '김치볶음밥', 'kimchi-rice.png', '김치볶음밥 설명', 3, " +
                            "'{\"en\":\"Kimchi Fried Rice\"}', '{}', '[]', 'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                            "VALUES (603, '된장찌개', '된장찌개', 'doenjang.png', '된장찌개 설명', 0, " +
                            "'{\"en\":\"Doenjang Stew\"}', '{}', '[]', 'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                }
            }
        }

        fun seedNumberedFoods(count: Int) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM member_ranking_event")
                    statement.execute("DELETE FROM food_review")
                    statement.execute("DELETE FROM food_content_outbox")
                statement.execute("DELETE FROM food_vector_outbox")
                statement.execute("DELETE FROM food_image")
                statement.execute("DELETE FROM food")
                    (1..count).forEach { index ->
                        statement.execute(
                            "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                                "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                                "VALUES (${700 + index}, '검색메뉴$index', '검색메뉴$index', 'menu-$index.png', '검색메뉴$index 설명', 0, " +
                                "'{}', '{}', '[]', 'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
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
                    statement.execute("DELETE FROM food_content_outbox")
                statement.execute("DELETE FROM food_vector_outbox")
                statement.execute("DELETE FROM food_image")
                statement.execute("DELETE FROM food")
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                            "VALUES (610, '냉면', '냉면', 'naengmyeon.png', '냉면 설명', 0, " +
                            "'{\"ja\":\"レイメン\",\"en\":\"Cold Noodles\"}', '{}', '[]', " +
                            "'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
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
                        "INSERT INTO member (id, provider, provider_uid, member_status, " +
                            "onboarding_completed, status, created_at, updated_at) " +
                            "VALUES ($memberId, 'GOOGLE', 'food-bm-$memberId', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) " +
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

        fun seedIngredient(foodId: Long, substanceCode: String, inclusionPercent: Int) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT IGNORE INTO ingredients " +
                            "(code, korean_name, translations, status, created_at, updated_at) " +
                            "VALUES ('$substanceCode', '$substanceCode', '{}', " +
                            "'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                    )
                    statement.execute(
                        "UPDATE food SET ingredients = JSON_ARRAY_APPEND(ingredients, '$', " +
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

                    val json = mockMvc.get("/api/foods/search") {
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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
                    seedIngredient(foodId = 601L, substanceCode = "SOY", inclusionPercent = 100)
                    FoodTestSeed.seedMemberAvoiding(dataSource, 11L, "SOY")
                    val token = tokenIssuer.issueAccessToken(11L, MemberRole.USER)

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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
                    seedIngredient(foodId = 601L, substanceCode = "SOY", inclusionPercent = 100)

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

                    mockMvc.get("/api/foods/search") {
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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
                        statement.execute("DELETE FROM food_content_outbox")
                statement.execute("DELETE FROM food_vector_outbox")
                statement.execute("DELETE FROM food_image")
                statement.execute("DELETE FROM food")
                        statement.execute(
                            "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                                "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                                "VALUES (620, '들깨칼국수', '들깨 칼국수', 'kalguksu.png', '들깨 칼국수 설명', 0, " +
                                "'{}', '{}', '[]', 'READY', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        )
                    }
                }
            }

            `when`("화면 표기 그대로(공백 포함) 검색하면") {
                then("표시명을 그대로 담은 결과를 돌려준다") {
                    seedSpacedFood()

                    mockMvc.get("/api/foods/search") {
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

                    mockMvc.get("/api/foods/search") {
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

        fun clearFoods() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    listOf("member_ranking_event", "food_review", "scan_history", "food_content_outbox", "food_vector_outbox", "food_image", "food")
                        .forEach { statement.execute("DELETE FROM $it") }
                    statement.execute(
                        "INSERT IGNORE INTO member (id, provider, provider_uid, nickname, member_status, onboarding_completed, status, created_at, updated_at) " +
                            "VALUES (7777, 'GOOGLE', 'search-scanner', '스캐너', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))",
                    )
                }
            }
        }

        fun seedFood(id: Long, koreanName: String, displayName: String = koreanName, translations: String = "{}", scans: Int = 0) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO food (id, korean_name, display_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, ingredients, content_status, status, created_at, updated_at) " +
                            "VALUES ($id, '$koreanName', '$displayName', 'f-$id.png', '설명', 0, '$translations', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))",
                    )
                    repeat(scans) {
                        statement.execute(
                            "INSERT INTO scan_history (member_id, food_id, status, created_at, updated_at) VALUES (7777, $id, 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }
                }
            }
        }

        fun search(keyword: String, lang: String = "ko", cursor: String? = null): String =
            mockMvc.get("/api/foods/search") {
                param("keyword", keyword)
                param("lang", lang)
                cursor?.let { param("cursor", it) }
            }.andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8)

        given("메뉴 검색 API — 관련도 정렬 (KB-721)") {
            `when`("정확 일치·앞부분 일치·포함 음식이 섞여 있으면") {
                then("정확 일치 → 앞부분 일치 → 포함 순이고, 같은 등급에서는 많이 스캔된 순, 그다음 id 내림차순이다") {
                    clearFoods()
                    seedFood(701, "돈까스떡볶이원조김밥", scans = 9)
                    seedFood(702, "참치김밥", scans = 9)
                    seedFood(703, "김밥", scans = 0)
                    seedFood(704, "김밥천국", scans = 1)
                    seedFood(705, "야채김밥", scans = 2)
                    seedFood(706, "치즈김밥", scans = 2)

                    foodIdsOf(search("김밥")) shouldBe listOf(703L, 704L, 706L, 705L, 702L, 701L)
                }
            }
        }

        given("메뉴 검색 API — 어느 언어로 쳐도 찾는다 (KB-721)") {
            `when`("ja 앱에서 로마자·한글·가나로 비빔밥을 찾으면") {
                then("네 검색어 모두 비빔밥을 찾는다") {
                    clearFoods()
                    seedFood(711, "비빔밥", translations = """{"en":"Bibimbap","ja":"ビビンバ","zh-Hans":"拌饭"}""")
                    seedFood(712, "김치찌개", translations = """{"en":"Kimchi Stew","ja":"キムチチゲ"}""")

                    listOf("bibimbap", "Bibimbap", "비빔밥", "ビビンバ", "拌饭").forEach { keyword ->
                        withClue(keyword) { foodIdsOf(search(keyword, lang = "ja")) shouldBe listOf(711L) }
                    }
                }
            }

            `when`("요청 언어가 아닌 언어의 이름으로 검색하면") {
                then("그래도 찾는다 — 이름 전부(한국어명·표시명·모든 번역)를 본다") {
                    seedJapaneseOnlyFood()

                    foodIdsOf(search("レイメン", lang = "en")) shouldBe listOf(610L)
                    foodIdsOf(search("Cold Noodles", lang = "ko")) shouldBe listOf(610L)
                }
            }
        }

        given("메뉴 검색 API — 공백·기호 무시 (KB-721)") {
            `when`("띄어쓰기만 다른 검색어로 찾으면") {
                then("같은 결과다 — 한국어는 한글만 남겨 비교하고, 다른 언어는 소문자·공백 축약으로 비교한다") {
                    clearFoods()
                    seedFood(721, "김치찌개", translations = """{"en":"Kimchi Stew"}""")
                    seedFood(722, "김치볶음밥", translations = """{"en":"Kimchi Fried Rice"}""")

                    foodIdsOf(search("김치 찌개")) shouldBe listOf(721L)
                    foodIdsOf(search("김치찌개")) shouldBe listOf(721L)
                    foodIdsOf(search("김치-찌개!")) shouldBe listOf(721L)
                    foodIdsOf(search("KIMCHI   stew", lang = "en")) shouldBe listOf(721L)
                }
            }
        }

        given("메뉴 검색 API — 복합 키셋 커서 (KB-721)") {
            `when`("같은 등급의 음식 45개를 커서로 끝까지 당기면") {
                then("중복·누락 없이 세 페이지에 전부 온다") {
                    clearFoods()
                    (1..45).forEach { seedFood(800L + it, "밥$it", scans = it % 5) }

                    val seen = mutableListOf<Long>()
                    var cursor: String? = null
                    var pages = 0
                    do {
                        val root = mapper.readTree(search("밥", cursor = cursor))
                        seen += root.path("payload").path("items").map { it.path("foodId").asLong() }
                        pages++
                        val hasNext = root.path("payload").path("hasNext").asBoolean()
                        cursor = root.path("payload").path("nextCursor").takeUnless { it.isNull }?.asText()
                        (hasNext == (cursor != null)) shouldBe true
                    } while (hasNext && pages < 10)

                    pages shouldBe 3
                    seen.size shouldBe 45
                    seen.toSet().size shouldBe 45
                    seen.take(9).map { (it - 800) % 5 }.toSet() shouldBe setOf(4L)
                }
            }

            `when`("구 형식 커서(숫자)가 오면") {
                then("400 이 아니라 첫 페이지다") {
                    clearFoods()
                    seedFood(731, "비빔밥")
                    seedFood(732, "돌솥비빔밥")

                    foodIdsOf(search("비빔밥", cursor = "732")) shouldBe foodIdsOf(search("비빔밥"))
                }
            }

            `when`("형식이 깨진 커서가 오면") {
                then("400 FOOD-002 다") {
                    mockMvc.get("/api/foods/search") {
                        param("keyword", "비빔밥")
                        param("lang", "ko")
                        param("cursor", "abc")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value(ErrorCode.INVALID_CURSOR.code) }
                    }
                }
            }
        }

        given("메뉴 검색 API — 지원 목록 밖 언어 코드는 영어로 폴백 (FR-004)") {
            `when`("존재하지 않는 언어 코드(lang=fr)로 검색하면") {
                then("400 이 아니라 영어 표시명으로 응답한다") {
                    seedSearchableFoods()

                    val json = mockMvc.get("/api/foods/search") {
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

                    val json = mockMvc.get("/api/foods/search") {
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

                    mockMvc.get("/api/foods/search?lang=ko") {
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

                    val firstJson = mockMvc.get("/api/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val firstIds = foodIdsOf(firstJson)
                    val nextCursor = mapper.readTree(firstJson).path("payload").path("nextCursor").asLong()

                    val secondJson = mockMvc.get("/api/foods/search?lang=ko") {
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

                    val firstJson = mockMvc.get("/api/foods/search?lang=ko") {
                        param("keyword", "검색메뉴")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val nextCursor = mapper.readTree(firstJson).path("payload").path("nextCursor").asLong()

                    val lastJson = mockMvc.get("/api/foods/search?lang=ko") {
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

                    mockMvc.get("/api/foods/search?lang=ko") {
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

                    mockMvc.get("/api/foods/search?lang=ko") {
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

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
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

                    mockMvc.get("/api/foods/search?lang=ko") {
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

                    mockMvc.get("/api/foods/search") {
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

                    mockMvc.get("/api/foods/search?lang=ko") {
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
        given("메뉴 검색 API — 리뷰 평점·리뷰 수") {
            fun seedReview(memberId: Long, foodId: Long, rating: Int) {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO member (id, provider, provider_uid, member_status, " +
                                "onboarding_completed, status, created_at, updated_at) " +
                                "VALUES ($memberId, 'GOOGLE', 'search-rating-$memberId', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) " +
                                "ON DUPLICATE KEY UPDATE id = id",
                        )
                        statement.execute(
                            "INSERT INTO food_review (member_id, food_id, rating, status, created_at, updated_at) " +
                                "VALUES ($memberId, $foodId, $rating, 'ACTIVE', NOW(6), NOW(6))",
                        )
                    }
                }
            }

            `when`("리뷰가 있는 음식을 검색하면") {
                then("검색 결과 항목에 평점·리뷰 수가 담긴다") {
                    seedSearchableFoods()
                    seedReview(320L, 601L, 4)
                    seedReview(321L, 601L, 5)

                    val json = mockMvc.get("/api/foods/search?lang=ko") {
                        param("keyword", "김치찌개")
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val item = mapper.readTree(json).path("payload").path("items").path(0)

                    item.path("review").path("averageRating").asDouble() shouldBe (4.5 plusOrMinus 0.0001)
                    item.path("review").path("count").asLong() shouldBe 2L
                }
            }
        }
    }
}
