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

        given("음식 상세 조회 — 간단·자세 설명 응답") {
            `when`("lang=en 으로 두 설명 번역이 모두 있는 메뉴를 조회하면") {
                then("간단·자세 설명을 영어로 응답에 포함한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.briefDescription") { value(FoodTestSeed.DOENJANG_BRIEF_EN) }
                        jsonPath("$.payload.detailedDescription") { value(FoodTestSeed.DOENJANG_DETAILED_EN) }
                    }
                }
            }

            `when`("lang 을 지정하지 않으면") {
                then("간단·자세 설명을 모두 한국어 원문으로 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.briefDescription") { value(FoodTestSeed.DOENJANG_BRIEF_KO) }
                        jsonPath("$.payload.detailedDescription") { value(FoodTestSeed.DOENJANG_DETAILED_KO) }
                    }
                }
            }

            `when`("lang=en 이지만 간단 설명 영어 번역만 부재하면") {
                then("간단 설명은 한국어로 폴백하고 자세한 설명·음식명은 영어를 유지한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "비빔밥")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("Bibimbap") }
                        jsonPath("$.payload.briefDescription") { value(FoodTestSeed.BIBIMBAP_BRIEF_KO) }
                        jsonPath("$.payload.detailedDescription") { value(FoodTestSeed.BIBIMBAP_DETAILED_EN) }
                    }
                }
            }

            `when`("음식 상세를 조회하면") {
                then("간단·자세 설명 필드가 응답에서 null 이 아니다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "en")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.briefDescription") { isNotEmpty() }
                        jsonPath("$.payload.detailedDescription") { isNotEmpty() }
                    }
                }
            }
        }
    }
}
