package com.kbap.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.llm.ExtractedMenu
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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ScanControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var vision: FakeMenuBoardVisionExtractor

    @Autowired
    private lateinit var similarSearch: FakeSimilarFoodSearch

    init {
        val mapper = jacksonObjectMapper()

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "scan-test-$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun setMemberCurrency(memberId: Long, currency: String) {
            seedMember(memberId)
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE member SET currency = ? WHERE id = ?").use { ps ->
                    ps.setString(1, currency)
                    ps.setLong(2, memberId)
                    ps.executeUpdate()
                }
            }
        }

        fun seedVerifiedImage(memberId: Long, path: String) {
            seedMember(memberId)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes,
                                                status, created_at, updated_at)
                    VALUES (?, ?, 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, path); ps.executeUpdate() }
            }
        }

        fun seedReadyFood(koreanName: String, nameTranslations: String = "{}"): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      ingredients, content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, ?, '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY', name_translations = VALUES(name_translations)
                    """,
                ).use { ps ->
                    ps.setString(1, koreanName)
                    ps.setString(2, nameTranslations)
                    ps.executeUpdate()
                }
            }

        fun deleteFood(matchKey: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "DELETE FROM food_content_outbox WHERE food_id IN (SELECT id FROM food WHERE korean_name = ?)",
                ).use { ps ->
                    ps.setString(1, matchKey)
                    ps.executeUpdate()
                }
                c.prepareStatement("DELETE FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, matchKey)
                    ps.executeUpdate()
                }
            }

        fun updateDisplayName(matchKey: String, displayName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement("UPDATE food SET display_name = ? WHERE korean_name = ?").use { ps ->
                    ps.setString(1, displayName); ps.setString(2, matchKey)
                    ps.executeUpdate()
                }
            }

        fun foodIdOf(koreanName: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
            }

        fun foodNames(matchKey: String): List<Pair<String, String>> =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT korean_name, display_name FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, matchKey)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
                }
            }

        fun pendingOutboxNames(matchKey: String): List<String> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT o.display_name FROM food_content_outbox o
                    JOIN food f ON f.id = o.food_id
                    WHERE f.korean_name = ? AND o.outbox_status = 'PENDING'
                    """,
                ).use { ps ->
                    ps.setString(1, matchKey)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            }

        fun scanCountOf(memberId: Long): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT scan_count FROM member WHERE id = ?").use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun historyRow(memberId: Long, koreanName: String): Triple<String, Int?, Long?> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT image_path, price, food_id FROM scan_history WHERE member_id = ? AND korean_name = ?",
                ).use { ps ->
                    ps.setLong(1, memberId); ps.setString(2, koreanName)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        val price = rs.getInt("price").takeUnless { rs.wasNull() }
                        val foodId = rs.getLong("food_id").takeUnless { rs.wasNull() }
                        Triple(rs.getString("image_path"), price, foodId)
                    }
                }
            }

        fun historyMenuName(memberId: Long, koreanName: String): String =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT menu_name FROM scan_history WHERE member_id = ? AND korean_name = ?",
                ).use { ps ->
                    ps.setLong(1, memberId); ps.setString(2, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            }

        fun body(imagePath: String, vararg items: Pair<Int, String>) =
            mapper.writeValueAsString(
                mapOf(
                    "imagePath" to imagePath,
                    "items" to items.map { mapOf("idx" to it.first, "rawMenuName" to it.second) },
                ),
            )

        given("메뉴판 사진 스캔 — POST /api/scans") {
            `when`("추출 메뉴가 클라이언트 OCR idx 에 매칭되면") {
                then("결과에 매칭된 client idx·위험도·가격이 담긴다") {
                    val memberId = 501L
                    val path = "scan/501/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("김치찌개", """{"en":"Kimchi Stew"}""")
                    vision.program(
                        path,
                        listOf(
                            ExtractedMenu("Kimchi 김치찌개", "김치찌개", 9000, matchedIdx = 0),
                            ExtractedMenu("Bulgogi 미등록불고기501", "미등록불고기501", 16000, matchedIdx = 1),
                        ),
                    )

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "김치찌개", 1 to "불고기")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.degraded") { value(false) }
                        jsonPath("$.payload.results.length()") { value(2) }
                        jsonPath("$.payload.results[0].idx") { value(0) }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("김치찌개") }
                        jsonPath("$.payload.results[0].koreanName") { value("김치찌개") }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                        jsonPath("$.payload.results[0].price") { value(9000) }
                        jsonPath("$.payload.results[1].idx") { value(1) }
                        jsonPath("$.payload.results[1].matched") { value(false) }
                        jsonPath("$.payload.results[1].name") { value("미등록불고기501") }
                        jsonPath("$.payload.results[1].koreanName") { value("미등록불고기501") }
                        jsonPath("$.payload.results[1].price") { value(16000) }
                    }
                }
            }

            `when`("lang=en 으로 영어 번역이 등록된 음식을 스캔하면") {
                then("매칭 항목의 name 은 영어 번역명으로 내려간다") {
                    val memberId = 514L
                    val path = "scan/514/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("번역김치찌개", """{"en":"Kimchi Stew"}""")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 번역김치찌개", "번역김치찌개", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "en")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "번역김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("Kimchi Stew") }
                        jsonPath("$.payload.results[0].koreanName") { value("번역김치찌개") }
                    }

                    historyMenuName(memberId, "번역김치찌개") shouldBe "Kimchi 번역김치찌개"
                }
            }

            `when`("등록되지 않은 신규 음식을 외국어 lang 으로 스캔하면") {
                then("사진 표기가 아니라 표준 한국어명으로 내려간다") {
                    val memberId = 522L
                    val path = "scan/522/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(
                        path,
                        listOf(ExtractedMenu("Bulgogi Hot Pot 신규불고기522", "신규불고기522", 16000, matchedIdx = 0)),
                    )

                    mockMvc.post("/api/scans") {
                        param("lang", "en")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "불고기")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].name") { value("신규불고기522") }
                        jsonPath("$.payload.results[0].koreanName") { value("신규불고기522") }
                    }
                }
            }

            `when`("lang=ja 로 같은 음식을 스캔하면") {
                then("매칭 항목의 name 은 일본어 번역명으로 내려간다") {
                    val memberId = 516L
                    val path = "scan/516/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("다국어김치찌개", """{"en":"Kimchi Stew","ja":"キムチチゲ"}""")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 다국어김치찌개", "다국어김치찌개", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ja")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "다국어김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].name") { value("キムチチゲ") }
                    }
                }
            }

            `when`("lang=en 인데 영어 번역이 없는 음식을 스캔하면") {
                then("매칭 항목의 name 은 한국어 이름으로 폴백한다") {
                    val memberId = 515L
                    val path = "scan/515/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("폴백김치찌개")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 폴백김치찌개", "폴백김치찌개", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "en")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "폴백김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("폴백김치찌개") }
                    }
                }
            }

            `when`("지원 목록에 없는 lang 으로 스캔하면") {
                then("거절하지 않고 영어 번역명으로 응답한다") {
                    val memberId = 518L
                    val path = "scan/518/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("미지원코드김치찌개", """{"en":"Fallback Stew"}""")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 미지원코드김치찌개", "미지원코드김치찌개", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "fr")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "미지원코드김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].name") { value("Fallback Stew") }
                    }
                }
            }

            `when`("대소문자·지역 변형처럼 지원 코드와 정확히 일치하지 않는 lang 으로 스캔하면") {
                then("정규화하지 않고 영어로 폴백한다") {
                    val memberId = 521L
                    val path = "scan/521/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("정규화없음김치찌개", """{"en":"No Normalize Stew"}""")

                    listOf("EN", "ko-KR", " ko").forEach { raw ->
                        vision.program(
                            path,
                            listOf(ExtractedMenu("Kimchi 정규화없음김치찌개", "정규화없음김치찌개", 9000, matchedIdx = 0)),
                        )

                        mockMvc.post("/api/scans") {
                            param("lang", raw)
                            header("Authorization", "Bearer ${accessToken(memberId)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = body(path, 0 to "정규화없음김치찌개")
                        }.andExpect {
                            status { isOk() }
                            jsonPath("$.payload.results[0].name") { value("No Normalize Stew") }
                        }
                    }
                }
            }

            `when`("lang 없이 스캔하면") {
                then("400 COMMON-002 로 거절한다") {
                    val memberId = 519L
                    val path = "scan/519/menu.jpg"
                    seedVerifiedImage(memberId, path)

                    mockMvc.post("/api/scans") {
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "김치찌개")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("lang 이 공백 문자열이면") {
                then("400 COMMON-002 로 거절한다") {
                    val memberId = 520L
                    val path = "scan/520/menu.jpg"
                    seedVerifiedImage(memberId, path)

                    mockMvc.post("/api/scans") {
                        param("lang", "  ")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "김치찌개")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("추출됐지만 대응하는 OCR 항목이 없으면") {
                then("그 항목의 idx 는 null 로 내려간다") {
                    val memberId = 511L
                    val path = "scan/511/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(
                        path,
                        listOf(
                            ExtractedMenu("공기밥", "공기밥", 1000, matchedIdx = 0),
                            ExtractedMenu("서비스반찬", "서비스반찬", null, matchedIdx = 99),
                        ),
                    )

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "공기밥")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].idx") { value(0) }
                        jsonPath("$.payload.results[1].idx") { value(null) }
                    }
                }
            }

            `when`("두 추출 메뉴가 같은 OCR idx 를 물고 오면") {
                then("먼저 나온 항목만 idx 를 갖고 뒤 항목은 null 이며 두 메뉴 모두 남는다") {
                    val memberId = 540L
                    val path = "scan/540/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(
                        path,
                        listOf(
                            ExtractedMenu("김치찌개(소)", "김치찌개소540", 8000, matchedIdx = 0),
                            ExtractedMenu("김치찌개(대)", "김치찌개대540", 12000, matchedIdx = 0),
                        ),
                    )

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results.length()") { value(2) }
                        jsonPath("$.payload.results[0].idx") { value(0) }
                        jsonPath("$.payload.results[1].idx") { value(null) }
                        jsonPath("$.payload.results[1].koreanName") { value("김치찌개대540") }
                    }
                }
            }

            `when`("클라이언트 OCR 텍스트가 사진과 전혀 다르면") {
                then("응답의 name·koreanName 어디에도 OCR 텍스트가 섞이지 않는다") {
                    val memberId = 541L
                    val path = "scan/541/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, listOf(ExtractedMenu("된장찌개", "된장찌개541", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "XXX오탈자XXX")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results.length()") { value(1) }
                        jsonPath("$.payload.results[0].idx") { value(0) }
                        jsonPath("$.payload.results[0].name") { value("된장찌개541") }
                        jsonPath("$.payload.results[0].koreanName") { value("된장찌개541") }
                    }
                }
            }

            `when`("가격이 표기되지 않은 메뉴가 섞여 있으면") {
                then("그 메뉴의 price 는 null 로 내려간다") {
                    val memberId = 502L
                    val path = "scan/502/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, listOf(ExtractedMenu("공기밥", "공기밥", null, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "공기밥")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].price") { value(null) }
                    }
                }
            }

            `when`("메뉴판이 아닌 사진이라 추출 항목이 0개면") {
                then("빈 results 로 정상 응답한다(실패 아님)") {
                    val memberId = 503L
                    val path = "scan/503/landscape.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, emptyList())

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "풍경")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results.length()") { value(0) }
                    }
                }
            }

            `when`("스캔이 성공하면") {
                then("전 추출 항목이 이미지 경로·가격과 함께 이력으로 저장되고 스캔 횟수가 오른다") {
                    val memberId = 504L
                    val path = "scan/504/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, listOf(ExtractedMenu("제육볶음", "이력제육볶음", 8000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "제육볶음")
                    }.andExpect { status { isOk() } }

                    val (imagePath, price, foodId) = historyRow(memberId, "이력제육볶음")
                    imagePath shouldBe path
                    price shouldBe 8000
                    (foodId != null) shouldBe true
                    scanCountOf(memberId) shouldBe 1
                }
            }

            xwhen("검증되지 않은(신고 안 된) 이미지 경로로 스캔하면") {
                then("400 SCAN-001 로 거절한다") {
                    val memberId = 505L
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/505/unverified.jpg", 0 to "김치찌개")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("SCAN-001") }
                    }
                }
            }

            xwhen("다른 회원이 업로드한 이미지 경로로 스캔하면") {
                then("본인 소유가 아니므로 400 SCAN-001 로 거절한다") {
                    val ownerId = 506L
                    val otherId = 507L
                    val path = "scan/506/owned.jpg"
                    seedVerifiedImage(ownerId, path)

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(otherId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "비빔밥")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("SCAN-001") }
                    }
                }
            }

            `when`("비전 인식이 실패하면") {
                then("503 SCAN-002 로 응답한다") {
                    val memberId = 508L
                    val path = "scan/508/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.failOn(path)

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "김치찌개")
                    }.andExpect {
                        status { isServiceUnavailable() }
                        jsonPath("$.code") { value("SCAN-002") }
                    }
                }
            }

            `when`("경로 대신 전체 URL 을 넘기면") {
                then("400 으로 거절한다") {
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(509L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("https://cdn.example.com/scan/509/x.jpg", 0 to "김치찌개")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("items 가 비어 있으면") {
                then("400 으로 거절한다") {
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(512L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/512/x.jpg")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("items 의 idx 가 중복이면") {
                then("400 으로 거절한다") {
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(513L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/513/x.jpg", 0 to "김치찌개", 0 to "비빔밥")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/510/x.jpg", 0 to "김치찌개")
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }

        given("스캔 DB miss 적재 — 표시명(원본 표기) 보존") {
            `when`("띄어쓰기가 있는 미등록 메뉴를 스캔하면") {
                then("응답에 원본 표기가 담기고 DB 는 표시명·match key 를 나눠 저장한다") {
                    val memberId = 530L
                    val path = "scan/530/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("들깨칼국수")
                    vision.program(path, listOf(ExtractedMenu("Kalguksu 들깨 칼국수", "들깨 칼국수", 11000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "들깨 칼국수")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].name") { value("들깨 칼국수") }
                        jsonPath("$.payload.results[0].koreanName") { value("들깨 칼국수") }
                    }

                    foodNames("들깨칼국수") shouldBe listOf("들깨칼국수" to "들깨 칼국수")
                }
            }

            `when`("미등록 메뉴가 등록되면") {
                then("콘텐츠 수집 요청이 대기 상태로 함께 쌓인다") {
                    val memberId = 533L
                    val path = "scan/533/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("수집대기국수")
                    vision.program(path, listOf(ExtractedMenu("Guksu 수집대기국수", "수집대기국수", 8000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "수집대기국수")
                    }.andExpect { status { isOk() } }

                    pendingOutboxNames("수집대기국수") shouldBe listOf("수집대기국수")
                }
            }

            `when`("표기만 다른 같은 메뉴를 다시 스캔하면") {
                then("신규 음식 없이 먼저 저장된 표시명을 유지한다") {
                    val memberId = 531L
                    val path = "scan/531/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("순두부찌개")
                    vision.program(path, listOf(ExtractedMenu("Sundubu 순두부 찌개", "순두부 찌개", 9000, matchedIdx = 0)))
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "순두부 찌개")
                    }.andExpect { status { isOk() } }

                    vision.program(path, listOf(ExtractedMenu("Sundubu 순두부찌개", "순두부찌개", 9000, matchedIdx = 0)))
                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "순두부찌개")
                    }.andExpect { status { isOk() } }

                    foodNames("순두부찌개") shouldBe listOf("순두부찌개" to "순두부 찌개")
                }
            }

            `when`("매칭된 음식이 표시명을 갖고 있으면") {
                then("응답 name·koreanName 이 표시명으로 내려간다") {
                    val memberId = 532L
                    val path = "scan/532/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("표시명김치찌개")
                    updateDisplayName("표시명김치찌개", "표시명 김치찌개")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 표시명김치찌개", "표시명김치찌개", 9000, matchedIdx = 0)))

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "표시명김치찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("표시명 김치찌개") }
                        jsonPath("$.payload.results[0].koreanName") { value("표시명 김치찌개") }
                    }
                }
            }
        }

        given("스캔 v2 — POST /api/scans (X-API-Version 2.0) 서버 OCR") {
            beforeContainer { similarSearch.reset() }

            fun v2Body(imagePath: String) = mapper.writeValueAsString(mapOf("imagePath" to imagePath))

            fun v2Scan(
                memberId: Long,
                path: String,
                lang: String = "ko",
                currency: String? = "USD",
                content: String = v2Body(path),
            ) =
                mockMvc.post("/api/scans") {
                    param("lang", lang)
                    currency?.let { param("currency", it) }
                    header("Authorization", "Bearer ${accessToken(memberId)}")
                    header("X-API-Version", "2.0")
                    contentType = MediaType.APPLICATION_JSON
                    this.content = content
                }

            `when`("items 없이 사진 경로만으로 스캔하면") {
                then("200 으로 응답하고 서버 추출 결과가 idx null 로 내려가며 이력·카운트가 기록된다") {
                    val memberId = 601L
                    val path = "scan/601/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("서버김치찌개", """{"en":"Server Kimchi Stew"}""")
                    vision.program(path, listOf(ExtractedMenu("Kimchi 서버김치찌개", "서버김치찌개", 9000, matchedIdx = null)))

                    v2Scan(memberId, path).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results.length()") { value(1) }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("서버김치찌개") }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                        jsonPath("$.payload.results[0].similarFood") { value(null) }
                    }

                    vision.receivedOcrItems[path] shouldBe emptyList()
                    val (imagePath, price, _) = historyRow(memberId, "서버김치찌개")
                    imagePath shouldBe path
                    price shouldBe 9000
                    scanCountOf(memberId) shouldBe 1
                }
            }

            `when`("메뉴판이 아닌 사진이라 추출 항목이 0개면") {
                then("400 SCAN-003 으로 거절한다") {
                    val memberId = 620L
                    val path = "scan/620/landscape.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, emptyList())

                    v2Scan(memberId, path).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("SCAN-003") }
                    }
                }
            }

            `when`("v2 요청 본문에 items 가 섞여 들어오면") {
                then("무시되고 서버 OCR(빈 힌트)로 추출한다") {
                    val memberId = 602L
                    val path = "scan/602/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, listOf(ExtractedMenu("공기밥", "공기밥", 1000, matchedIdx = null)))

                    v2Scan(memberId, path, content = body(path, 0 to "공기밥")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                    }

                    vision.receivedOcrItems[path] shouldBe emptyList()
                }
            }

            `when`("미등록 메뉴에 충분히 비슷한 등록 음식이 있으면") {
                then("similarFood 에 그 음식의 요청 언어명·설명·식별자가 담긴다") {
                    val memberId = 603L
                    val path = "scan/603/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("할머니손맛찌개603")
                    seedReadyFood("유사김치찌개603", """{"en":"Similar Kimchi Stew"}""")
                    val similarFoodId = foodIdOf("유사김치찌개603")
                    vision.program(path, listOf(ExtractedMenu("할머니손맛찌개", "할머니손맛찌개603", 12000, matchedIdx = null)))
                    similarSearch.program("할머니손맛찌개603", similarFoodId, score = 0.92)

                    v2Scan(memberId, path, lang = "en").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].similarFood.foodId") { value(similarFoodId) }
                        jsonPath("$.payload.results[0].similarFood.name") { value("Similar Kimchi Stew") }
                        jsonPath("$.payload.results[0].similarFood.koreanName") { value("유사김치찌개603") }
                        jsonPath("$.payload.results[0].similarFood.description") { value("설명") }
                    }
                }
            }

            `when`("유사 음식의 foodId 로 상세를 조회하면") {
                then("정상 조회된다 — 유사 대체는 항상 등록 음식이다") {
                    val memberId = 604L
                    val path = "scan/604/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("모르는찌개604")
                    seedReadyFood("유사김치찌개604")
                    val similarFoodId = foodIdOf("유사김치찌개604")
                    vision.program(path, listOf(ExtractedMenu("모르는찌개", "모르는찌개604", null, matchedIdx = null)))
                    similarSearch.program("모르는찌개604", similarFoodId, score = 0.9)

                    v2Scan(memberId, path).andExpect { status { isOk() } }

                    mockMvc.get("/api/foods/$similarFoodId") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                    }.andExpect { status { isOk() } }
                }
            }

            `when`("유사도 점수가 임계 미만이면") {
                then("similarFood 없이 미등록 응답한다") {
                    val memberId = 605L
                    val path = "scan/605/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("동떨어진메뉴605")
                    seedReadyFood("유사김치찌개605")
                    vision.program(path, listOf(ExtractedMenu("동떨어진메뉴", "동떨어진메뉴605", null, matchedIdx = null)))
                    similarSearch.program("동떨어진메뉴605", foodIdOf("유사김치찌개605"), score = -1.0)

                    v2Scan(memberId, path).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].similarFood") { value(null) }
                    }
                }
            }

            `when`("검색된 foodId 가 조회 불가(미등록·삭제)면") {
                then("similarFood 없이 미등록 응답한다") {
                    val memberId = 606L
                    val path = "scan/606/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("고아메뉴606")
                    vision.program(path, listOf(ExtractedMenu("고아메뉴", "고아메뉴606", null, matchedIdx = null)))
                    similarSearch.program("고아메뉴606", foodId = 999_999_999L, score = 0.95)

                    v2Scan(memberId, path).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].similarFood") { value(null) }
                    }
                }
            }

            `when`("벡터 검색이 실패하면") {
                then("스캔은 성공하고 해당 항목만 similarFood 없이 내려간다(부분 성공)") {
                    val memberId = 607L
                    val path = "scan/607/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("장애메뉴607")
                    vision.program(path, listOf(ExtractedMenu("장애메뉴", "장애메뉴607", null, matchedIdx = null)))
                    similarSearch.failSearch()

                    v2Scan(memberId, path).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].similarFood") { value(null) }
                    }
                }
            }

            `when`("imagePath 를 누락하면") {
                then("400 COMMON-002 로 거절한다") {
                    v2Scan(608L, "unused", content = "{}").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("v1 경로로 미등록 메뉴를 스캔하면") {
                then("응답에 similarFood 필드 자체가 없다(v1 동결 계약)") {
                    val memberId = 611L
                    val path = "scan/611/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("브이원미등록611")
                    seedReadyFood("유사김치찌개611")
                    vision.program(path, listOf(ExtractedMenu("브이원미등록", "브이원미등록611", null, matchedIdx = 0)))
                    similarSearch.program("브이원미등록611", foodIdOf("유사김치찌개611"), score = 0.99)

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "브이원미등록")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].similarFood") { doesNotExist() }
                    }
                }
            }

            `when`("currency 파라미터 없이 v2 스캔하면") {
                then("400 COMMON-002 로 거절하고 스캔은 실행되지 않는다") {
                    val memberId = 621L
                    val path = "scan/621/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    setMemberCurrency(memberId, "USD")
                    vision.program(path, listOf(ExtractedMenu("통화김치찌개", "통화김치찌개", 9000, matchedIdx = null)))

                    v2Scan(memberId, path, currency = null).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }

                    scanCountOf(memberId) shouldBe 0
                }
            }

            `when`("통화(USD)가 설정된 회원이 currency=JPY 파라미터로 v2 스캔하면") {
                then("프로필을 무시하고 파라미터 통화 기준 환산 정보가 담긴다") {
                    val memberId = 624L
                    val path = "scan/624/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("파라미터통화찌개")
                    vision.program(path, listOf(ExtractedMenu("파라미터통화찌개", "파라미터통화찌개", 9000, matchedIdx = null)))
                    setMemberCurrency(memberId, "USD")

                    val json = v2Scan(memberId, path, currency = "JPY").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.currency.code") { value("JPY") }
                    }.andReturn().response.contentAsString

                    val krwPerUnit = mapper.readTree(json)
                        .path("payload").path("currency").path("krwPerUnit").decimalValue()
                    krwPerUnit.compareTo(BigDecimal("8.8906")) shouldBe 0
                }
            }

            `when`("통화가 설정되지 않은 회원이 currency=USD 파라미터로 v2 스캔하면") {
                then("파라미터 통화 기준 환산 정보가 담긴다") {
                    val memberId = 625L
                    val path = "scan/625/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("무프로필파라미터찌개")
                    vision.program(path, listOf(ExtractedMenu("무프로필파라미터찌개", "무프로필파라미터찌개", 8000, matchedIdx = null)))

                    val json = v2Scan(memberId, path, currency = "USD").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.currency.code") { value("USD") }
                    }.andReturn().response.contentAsString

                    val krwPerUnit = mapper.readTree(json)
                        .path("payload").path("currency").path("krwPerUnit").decimalValue()
                    krwPerUnit.compareTo(BigDecimal("1416.0000")) shouldBe 0
                }
            }

            `when`("지원하지 않는 currency 값으로 v2 스캔하면") {
                then("400 MEMBER-010 으로 거절하고 스캔은 실행되지 않는다") {
                    val memberId = 626L
                    val path = "scan/626/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    vision.program(path, listOf(ExtractedMenu("차단찌개", "차단찌개626", 7000, matchedIdx = null)))

                    v2Scan(memberId, path, currency = "XXX").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("MEMBER-010") }
                    }

                    scanCountOf(memberId) shouldBe 0
                }
            }

            `when`("통화(USD)가 설정된 회원이 v1 경로로 스캔하면") {
                then("응답에 currency 필드 자체가 없다(v1 동결 계약)") {
                    val memberId = 623L
                    val path = "scan/623/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    seedReadyFood("브이원통화찌개")
                    vision.program(path, listOf(ExtractedMenu("브이원통화", "브이원통화찌개", 7000, matchedIdx = 0)))
                    setMemberCurrency(memberId, "USD")

                    mockMvc.post("/api/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "브이원통화")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.currency") { doesNotExist() }
                    }
                }
            }
        }
    }
}
