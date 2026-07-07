package com.meogo.app.api.food
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

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
@Import(MySqlContainerConfig::class)
class FoodDetailControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.seedDoenjangStew(dataSource) }

        given("음식 상세 조회 API") {
            `when`("lang=en 으로 수록된 foodId 를 조회하면") {
                then("200 과 동결 계약(ingredients[].{name,iconRef,inclusionPercent,riskStatus})으로 포함 기피 성분을 반환한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                        jsonPath("$.payload.description") { value(FoodTestSeed.DOENJANG_DESCRIPTION_EN) }
                        jsonPath("$.payload.spiciness") { value(FoodTestSeed.DOENJANG_SPICINESS) }
                        jsonPath("$.payload.overallRiskStatus") { value("DANGER") }
                        jsonPath("$.payload.ingredients.length()") { value(3) }
                        jsonPath("$.payload.ingredients[0].name") { value("Soybean") }
                        jsonPath("$.payload.ingredients[0].iconRef") { value(null) }
                        jsonPath("$.payload.ingredients[0].inclusionPercent") { value(100) }
                        jsonPath("$.payload.ingredients[0].riskStatus") { value("DANGER") }
                        jsonPath("$.payload.ingredients[1].name") { value("Wheat") }
                        jsonPath("$.payload.ingredients[1].inclusionPercent") { value(80) }
                        jsonPath("$.payload.ingredients[1].riskStatus") { value("DANGER") }
                        jsonPath("$.payload.ingredients[2].name") { value("Clam") }
                        jsonPath("$.payload.ingredients[2].iconRef") { value(null) }
                        jsonPath("$.payload.ingredients[2].inclusionPercent") { value(50) }
                        jsonPath("$.payload.ingredients[2].riskStatus") { value("CAUTION") }
                    }
                }
            }

            `when`("포함 기피 성분이 하나도 없는 foodId 를 조회하면") {
                then("200 과 함께 ingredients 를 빈 배열로 반환한다") {
                    FoodTestSeed.seedPlainRice(dataSource)

                    mockMvc.get("/api/v1/foods/3") {
                        param("lang", "ko")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.name") { value("흰밥") }
                        jsonPath("$.payload.overallRiskStatus") { value("SAFE") }
                        jsonPath("$.payload.ingredients.length()") { value(0) }
                    }
                }
            }
        }
    }
}
