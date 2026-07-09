package com.meogo.app.api.scan

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
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
class MenuScanControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val objectMapper = jacksonObjectMapper()

        fun seedReadyFood(koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE content_status = 'READY'
                    """,
                ).use { ps -> ps.setString(1, koreanName); ps.executeUpdate() }
            }

        fun seedTranslatedFood(koreanName: String, englishName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      content_status, status, created_at, updated_at)
                    VALUES (?, '설명', 0, ?, '{}', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE name_translations = VALUES(name_translations)
                    """,
                ).use { ps ->
                    ps.setString(1, koreanName)
                    ps.setString(2, """{"en": "$englishName"}""")
                    ps.executeUpdate()
                }
            }

        fun countFood(koreanName: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun contentStatusOf(koreanName: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT content_status FROM food WHERE korean_name = ?").use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun item(idx: Int, name: String) = mapOf(
            "idx" to idx,
            "rawMenuName" to name,
        )

        fun body(vararg items: Map<String, Any>) =
            objectMapper.writeValueAsString(mapOf("items" to items.toList()))

        given("메뉴 스캔 제출 API — 요청/응답 규약") {
            `when`("유효한 항목들로 POST /api/v1/menu-scans 를 호출하면") {
                then("200 과 idx 1:1 매칭 결과를 반환한다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "된장찌개"), item(1, "비빔밥"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.degraded") { value(true) }
                        jsonPath("$.payload.results.length()") { value(2) }
                        jsonPath("$.payload.results[0].idx") { value(0) }
                        jsonPath("$.payload.results[1].idx") { value(1) }
                    }
                }
            }

            `when`("한글이 전혀 없는 항목을 제출하면") {
                then("메뉴가 아니므로 결과에서 제외된다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "6,500"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results.length()") { value(0) }
                    }
                }
            }
        }

        given("정제 서비스 호출 실패(폴백) — 실제 매칭 e2e") {
            `when`("저장된 완성 음식과 정규화 키가 일치하면") {
                then("MATCHED 이고 위험도를 산출해 반환한다") {
                    seedReadyFood("완성이이김치찌개")

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "완성이이김치찌개 kimchi jjigae"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].foodId") { exists() }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                    }
                }
            }

            `when`("정제 서비스가 죽어 음식 여부를 판정할 수 없으면") {
                then("PENDING·UNKNOWN 이고 food 테이블을 오염시키지 않는다") {
                    val name = "폴백미상-우주라면"

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].foodId") { doesNotExist() }
                    }

                    countFood(name) shouldBe 0
                }
            }

            `when`("미완성으로 등록된 음식과 키가 일치하면") {
                then("MATCHED 가 아니라 UNMATCHED·UNKNOWN 으로 응답한다") {
                    val name = "미완성-마라샹궈"
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            """
                            INSERT INTO food (korean_name, description, spiciness, name_translations,
                                              description_translations, content_status, status, created_at, updated_at)
                            VALUES (?, '설명 준비 중', 0, '{}', '{}', 'INCOMPLETE', 'ACTIVE', NOW(6), NOW(6))
                            ON DUPLICATE KEY UPDATE content_status = 'INCOMPLETE'
                            """,
                        ).use { ps -> ps.setString(1, name); ps.executeUpdate() }
                    }

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].foodId") { exists() }
                    }

                    contentStatusOf(name) shouldBe "INCOMPLETE"
                }
            }
        }

        given("응답 메뉴명 — 현재는 한국어 고정(회원 언어 설정 연동 전)") {
            `when`("매칭된 음식에 en 번역이 있어도") {
                then("name 을 한국어로 내린다") {
                    seedTranslatedFood("언어테스트김치찌개", "Kimchi Stew")

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "언어테스트김치찌개 kimchi jjigae"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(true) }
                        jsonPath("$.payload.results[0].name") { value("언어테스트김치찌개") }
                        jsonPath("$.payload.results[0].koreanName") { value("언어테스트김치찌개") }
                    }
                }
            }

            `when`("조사 대기(미완성) 음식이면") {
                then("표준명을 name·koreanName 에 함께 담는다") {
                    val name = "언어테스트미완성-마라탕"
                    dataSource.connection.use { c ->
                        c.prepareStatement(
                            """
                            INSERT INTO food (korean_name, description, spiciness, name_translations,
                                              description_translations, content_status, status, created_at, updated_at)
                            VALUES (?, '설명 준비 중', 0, '{}', '{}', 'INCOMPLETE', 'ACTIVE', NOW(6), NOW(6))
                            ON DUPLICATE KEY UPDATE content_status = 'INCOMPLETE'
                            """,
                        ).use { ps -> ps.setString(1, name); ps.executeUpdate() }
                    }

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matched") { value(false) }
                        jsonPath("$.payload.results[0].name") { value(name) }
                        jsonPath("$.payload.results[0].koreanName") { value(name) }
                    }
                }
            }

            `when`("번역이 있는 음식을 다시 조회해도") {
                then("name 이 한국어다") {
                    seedTranslatedFood("언어미지정김치찌개", "Kimchi Stew")

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "언어미지정김치찌개"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].name") { value("언어미지정김치찌개") }
                    }
                }
            }
        }
    }
}
