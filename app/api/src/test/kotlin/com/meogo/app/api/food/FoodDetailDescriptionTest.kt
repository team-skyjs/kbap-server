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
class FoodDetailDescriptionTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest {
            FoodTestSeed.seedDoenjangStew(dataSource)
            FoodTestSeed.seedPartialDescriptionFood(dataSource)
        }

        given("음식 상세 조회 — 단일 설명·맵기 응답") {
            `when`("lang=en 으로 설명 번역이 있는 메뉴를 조회하면") {
                then("설명을 영어로, 맵기를 정수로 응답에 포함한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.description") { value(FoodTestSeed.DOENJANG_DESCRIPTION_EN) }
                        jsonPath("$.payload.spiciness") { value(FoodTestSeed.DOENJANG_SPICINESS) }
                    }
                }
            }

            `when`("lang 을 지정하지 않으면") {
                then("설명을 한국어 원문으로 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.description") { value(FoodTestSeed.DOENJANG_DESCRIPTION_KO) }
                    }
                }
            }

            `when`("lang=en 이지만 설명 번역이 부재하면") {
                then("설명은 한국어로 폴백하고 음식명은 영어를 유지한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "비빔밥")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("Bibimbap") }
                        jsonPath("$.payload.description") { value(FoodTestSeed.BIBIMBAP_DESCRIPTION_KO) }
                        jsonPath("$.payload.spiciness") { value(FoodTestSeed.BIBIMBAP_SPICINESS) }
                    }
                }
            }

            `when`("음식 상세를 조회하면") {
                then("간단·자세 설명 필드는 응답에서 사라지고 단일 설명 필드만 존재한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.description") { isNotEmpty() }
                        jsonPath("$.payload.briefDescription") { doesNotExist() }
                        jsonPath("$.payload.detailedDescription") { doesNotExist() }
                    }
                }
            }
        }
    }
}
