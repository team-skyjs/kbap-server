package com.meogo.app.api.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
class FoodDetailControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.seedDoenjangStew(dataSource) }

        given("음식 상세 조회 API") {
            `when`("lang=en 으로 수록된 메뉴를 조회하면") {
                then("200 과 영어 음식명·재료(%·riskStatus)를 payload 봉투로 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                        jsonPath("$.payload.ingredients.length()") { value(3) }
                        jsonPath("$.payload.ingredients[0].name") { value("Soybean paste") }
                        jsonPath("$.payload.ingredients[0].inclusionPercent") { value(100) }
                        jsonPath("$.payload.ingredients[0].riskStatus") { value("CAUTION") }
                        jsonPath("$.payload.ingredients[1].riskStatus") { value("SAFE") }
                        jsonPath("$.payload.ingredients[2].name") { value("Manila clam") }
                        jsonPath("$.payload.ingredients[2].inclusionPercent") { value(50) }
                    }
                }
            }

            `when`("메뉴명 앞뒤에 공백이 있으면") {
                then("trim 후 매칭해 200 을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "  된장찌개  ")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                    }
                }
            }
        }
    }
}
