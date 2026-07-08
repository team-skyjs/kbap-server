package com.meogo.app.api.scan
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
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

        fun seedFood(koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (korean_name, description, spiciness, name_translations, description_translations,
                                      status, created_at, updated_at)
                    VALUES (?, '설명', 0, '{}', '{}', 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setString(1, koreanName)
                    ps.executeUpdate()
                }
            }

        fun pendingCount(standardName: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM pending_menus WHERE standard_name = ?").use { ps ->
                    ps.setString(1, standardName)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun item(itemId: Int, name: String = "메뉴$itemId") = mapOf(
            "itemId" to itemId,
            "rawMenuName" to name,
            "boundingBox" to mapOf("x" to 0.1, "y" to 0.1, "width" to 0.3, "height" to 0.1),
        )

        fun body(vararg items: Map<String, Any>) =
            objectMapper.writeValueAsString(mapOf("items" to items.toList()))

        given("메뉴 스캔 제출 API") {
            `when`("유효한 4개 항목으로 POST /api/v1/menu-scans 를 호출하면") {
                then("200 과 itemId 1:1 매칭, 4단계 분포를 반환한다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0), item(1), item(2), item(3))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.scanId") { exists() }
                        jsonPath("$.payload.results.length()") { value(4) }
                        jsonPath("$.payload.results[0].id") { exists() }
                        jsonPath("$.payload.results[0].itemId") { value(0) }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                        jsonPath("$.payload.results[0].matchStatus") { value("PENDING") }
                        jsonPath("$.payload.results[0].foodId") { doesNotExist() }
                        jsonPath("$.payload.results[1].riskLevel") { value("CAUTION") }
                        jsonPath("$.payload.results[2].riskLevel") { value("DANGER") }
                        jsonPath("$.payload.results[3].riskLevel") { value("UNKNOWN") }
                    }
                }
            }

            `when`("같은 메뉴명이지만 itemId 가 다른 항목들을 제출하면") {
                then("각 itemId 로 구분 매칭된다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(10, "된장찌개"), item(20, "된장찌개"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].itemId") { value(10) }
                        jsonPath("$.payload.results[1].itemId") { value(20) }
                        jsonPath("$.payload.results[0].riskLevel") { value("SAFE") }
                        jsonPath("$.payload.results[1].riskLevel") { value("CAUTION") }
                    }
                }
            }

            `when`("5개 항목을 제출하면") {
                then("index 4 항목은 SAFE 로 재순환한다") {
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0), item(1), item(2), item(3), item(4))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[4].riskLevel") { value("SAFE") }
                    }
                }
            }
        }

        given("정제 서비스 미구성(폴백) — 실제 매칭 e2e") {
            `when`("저장된 음식 이름과 정규화 키가 일치하면") {
                then("MATCHED 로 foodId 와 함께 반환한다") {
                    seedFood("김치찌개")

                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, "김치찌개 kimchi jjigae"))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matchStatus") { value("MATCHED") }
                        jsonPath("$.payload.results[0].foodId") { exists() }
                    }
                }
            }

            `when`("미등록 메뉴를 두 번 스캔하면") {
                then("PENDING 이고 대기열에는 1건만 적재된다(dedup)") {
                    val name = "정제e2e-미등록라면"
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.results[0].matchStatus") { value("PENDING") }
                    }
                    mockMvc.post("/api/v1/menu-scans") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body(item(0, name))
                    }.andExpect { status { isOk() } }

                    pendingCount(name) shouldBe 1
                }
            }
        }
    }
}
