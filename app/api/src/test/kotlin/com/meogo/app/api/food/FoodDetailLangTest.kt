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
class FoodDetailLangTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.seedDoenjangStew(dataSource) }

        given("음식 상세 조회 다국어 처리") {
            `when`("lang=ja 로 조회하면") {
                then("일본어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "ja")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("テンジャンチゲ") }
                    }
                }
            }

            `when`("lang=ja 인데 성분에 일본어 번역이 없으면") {
                then("성분 표시명을 한국어로 폴백하고 확률 내림차순을 유지한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "ja")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.ingredients[0].name") { value("대두") }
                        jsonPath("$.payload.ingredients[0].inclusionPercent") { value(100) }
                        jsonPath("$.payload.ingredients[2].name") { value("조개") }
                        jsonPath("$.payload.ingredients[2].inclusionPercent") { value(50) }
                    }
                }
            }

            `when`("지원하지 않는 lang=xx 로 조회하면") {
                then("400 과 실패 응답을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "xx")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                    }
                }
            }

            `when`("lang 을 지정하지 않으면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }

            `when`("lang 을 빈 값으로 조회하면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }

            `when`("lang 을 공백 문자열로 조회하면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "   ")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }
        }
    }
}
