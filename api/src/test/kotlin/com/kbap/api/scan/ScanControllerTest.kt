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
import org.springframework.test.web.servlet.post
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

        fun foodNames(matchKey: String): List<Pair<String, String>> =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT korean_name, display_name FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, matchKey)
                    ps.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                    }
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

        given("메뉴판 사진 스캔 — POST /api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                        mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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
                            // LLM 이 목록에 없는 idx(99)를 준 경우도 서버가 null 로 방어한다.
                            ExtractedMenu("서비스반찬", "서비스반찬", null, matchedIdx = 99),
                        ),
                    )

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

            // TODO ScanService 의 소유 검증(verifyImageAccess) 주석 해제 시 xwhen → when 으로 함께 복구
            xwhen("검증되지 않은(신고 안 된) 이미지 경로로 스캔하면") {
                then("400 SCAN-001 로 거절한다") {
                    val memberId = 505L
                    mockMvc.post("/api/v1/scans") {
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

            // TODO ScanService 의 소유 검증(verifyImageAccess) 주석 해제 시 xwhen → when 으로 함께 복구
            xwhen("다른 회원이 업로드한 이미지 경로로 스캔하면") {
                then("본인 소유가 아니므로 400 SCAN-001 로 거절한다") {
                    val ownerId = 506L
                    val otherId = 507L
                    val path = "scan/506/owned.jpg"
                    seedVerifiedImage(ownerId, path)

                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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
                    mockMvc.post("/api/v1/scans") {
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
                    mockMvc.post("/api/v1/scans") {
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
                    mockMvc.post("/api/v1/scans") {
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
                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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

            `when`("표기만 다른 같은 메뉴를 다시 스캔하면") {
                then("신규 음식 없이 먼저 저장된 표시명을 유지한다") {
                    val memberId = 531L
                    val path = "scan/531/menu.jpg"
                    seedVerifiedImage(memberId, path)
                    deleteFood("순두부찌개")
                    vision.program(path, listOf(ExtractedMenu("Sundubu 순두부 찌개", "순두부 찌개", 9000, matchedIdx = 0)))
                    mockMvc.post("/api/v1/scans") {
                        param("lang", "ko")
                        header("Authorization", "Bearer ${accessToken(memberId)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, 0 to "순두부 찌개")
                    }.andExpect { status { isOk() } }

                    vision.program(path, listOf(ExtractedMenu("Sundubu 순두부찌개", "순두부찌개", 9000, matchedIdx = 0)))
                    mockMvc.post("/api/v1/scans") {
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

                    mockMvc.post("/api/v1/scans") {
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
    }
}
