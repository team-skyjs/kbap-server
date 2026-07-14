package com.kbap.app.api.food
import com.kbap.core.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.kbap.application.auth.token.TokenIssuer
import com.kbap.domain.member.model.MemberRole
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

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        beforeTest { FoodTestSeed.seedDoenjangStew(dataSource) }

        given("음식 상세 조회 API") {
            `when`("SOY 를 회피하는 회원이 lang=en 으로 수록된 foodId 를 조회하면") {
                then("200 과 함께 회원 기피 성분과 겹치는 성분만 동결 계약(ingredients[].{name,iconRef,inclusionPercent,riskStatus})으로 반환한다") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 11L, "SOY")
                    val token = tokenIssuer.issueAccessToken(11L, MemberRole.USER)

                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "en")
                        header("Authorization", "Bearer $token")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                        jsonPath("$.payload.description") { value(FoodTestSeed.DOENJANG_DESCRIPTION_EN) }
                        jsonPath("$.payload.spiciness") { value(FoodTestSeed.DOENJANG_SPICINESS) }
                        jsonPath("$.payload.overallRiskStatus") { value("DANGER") }
                        jsonPath("$.payload.ingredients.length()") { value(1) }
                        jsonPath("$.payload.ingredients[0].name") { value("Soybean") }
                        jsonPath("$.payload.ingredients[0].iconRef") { value(null) }
                        jsonPath("$.payload.ingredients[0].inclusionPercent") { value(100) }
                        jsonPath("$.payload.ingredients[0].riskStatus") { value("DANGER") }
                    }
                }
            }

            `when`("SOY 와 CLAM 을 회피하는 회원이 조회하면") {
                then("겹치는 두 성분만 확률 내림차순으로 반환하고 WHEAT 는 제외한다") {
                    FoodTestSeed.seedMemberAvoiding(dataSource, 12L, "SOY", "CLAM")
                    val token = tokenIssuer.issueAccessToken(12L, MemberRole.USER)

                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "en")
                        header("Authorization", "Bearer $token")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.ingredients.length()") { value(2) }
                        jsonPath("$.payload.ingredients[0].name") { value("Soybean") }
                        jsonPath("$.payload.ingredients[0].inclusionPercent") { value(100) }
                        jsonPath("$.payload.ingredients[0].riskStatus") { value("DANGER") }
                        jsonPath("$.payload.ingredients[1].name") { value("Clam") }
                        jsonPath("$.payload.ingredients[1].inclusionPercent") { value(50) }
                        jsonPath("$.payload.ingredients[1].riskStatus") { value("CAUTION") }
                    }
                }
            }

            `when`("비회원이 성분이 있는 foodId 를 조회하면") {
                then("교집합이 없으므로 ingredients 는 빈 배열이고 overallRiskStatus 는 SAFE 다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.overallRiskStatus") { value("SAFE") }
                        jsonPath("$.payload.ingredients.length()") { value(0) }
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
