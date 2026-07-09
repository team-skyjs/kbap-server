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

        fun item(itemId: Int, name: String) = mapOf(
            "itemId" to itemId,
            "rawMenuName" to name,
            "boundingBox" to mapOf("x" to 0.1, "y" to 0.1, "width" to 0.3, "height" to 0.1),
        )

        fun body(vararg items: Map<String, Any>) =
            objectMapper.writeValueAsString(mapOf("items" to items.toList()))

        given("메뉴 스캔 제출 API — 요청/응답 규약") {
            `when`("유효한 항목들로 POST /api/v1/menu-scans 를 호출하면") {
                then("200 과 itemId 1:1 매칭 결과를 반환한다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "된장찌개"), item(1, "비빔밥"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.scanId") { exists() }
                        jsonPath("$.payload.results.length()") { value(2) }
                        jsonPath("$.payload.results[0].id") { exists() }
                        jsonPath("$.payload.results[0].itemId") { value(0) }
                        jsonPath("$.payload.results[1].itemId") { value(1) }
                    }
                }
            }

            `when`("한글이 전혀 없는 항목을 제출하면") {
                then("NOT_FOOD·UNKNOWN 이고 foodId 는 없다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "6,500"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matchStatus") { value("NOT_FOOD") }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].foodId") { doesNotExist() }
                    }
                }
            }
        }

        given("정제 서비스 미구성(폴백) — 실제 매칭 e2e") {
            `when`("저장된 완성 음식과 정규화 키가 일치하면") {
                then("MATCHED 이고 위험도를 산출해 반환한다") {
                    seedReadyFood("완성이이김치찌개")

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "완성이이김치찌개 kimchi jjigae"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matchStatus") { value("MATCHED") }
                        jsonPath("$.payload.results[0].foodId") { exists() }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                    }
                }
            }

            `when`("정제 서비스가 없어 음식 여부를 판정할 수 없으면") {
                then("PENDING·UNKNOWN 이고 food 테이블을 오염시키지 않는다") {
                    val name = "폴백미상-우주라면"

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matchStatus") { value("PENDING") }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].foodId") { doesNotExist() }
                    }

                    countFood(name) shouldBe 0
                }
            }

            `when`("미완성으로 등록된 음식과 키가 일치하면") {
                then("MATCHED 가 아니라 PENDING·UNKNOWN 으로 응답한다") {
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
                        jsonPath("$.payload.results[0].matchStatus") { value("PENDING") }
                        jsonPath("$.payload.results[0].riskLevel") { value("UNKNOWN") }
                        jsonPath("$.payload.results[0].foodId") { exists() }
                    }

                    contentStatusOf(name) shouldBe "INCOMPLETE"
                }
            }
        }
    }
}
