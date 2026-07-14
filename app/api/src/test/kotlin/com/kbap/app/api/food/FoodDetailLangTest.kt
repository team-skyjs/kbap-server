package com.kbap.app.api.food
import com.kbap.core.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
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
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "ja")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("テンジャンチゲ") }
                    }
                }
            }

            `when`("lang=ja 인데 성분에 일본어 번역이 없으면") {
                then("성분 표시명을 한국어로 폴백하고 확률 내림차순을 유지한다") {
                    mockMvc.get("/api/v1/foods/1") {
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
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "xx")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                    }
                }
            }

            `when`("lang 을 지정하지 않으면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/1").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }

            `when`("lang 을 빈 값으로 조회하면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }

            `when`("lang 을 공백 문자열로 조회하면") {
                then("ko 로 기본 처리해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "   ")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }
        }

        given("음식 상세 조회 — 언어 무관 한국어 메뉴명(koreanName)") {
            `when`("lang=ja 로 조회하면(지역화명이 한국어와 다름)") {
                then("지역화명은 일본어이고 koreanName 에 한국어 원문을 담는다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "ja")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("テンジャンチゲ") }
                        jsonPath("$.payload.koreanName") { value("된장찌개") }
                    }
                }
            }

            `when`("lang=ko 로 조회하면(지역화명이 곧 한국어)") {
                then("koreanName 은 응답에 명시적 null 로 존재한다") {
                    val json = mockMvc.get("/api/v1/foods/1") {
                        param("lang", "ko")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    val payload = jacksonObjectMapper().readTree(json).path("payload")
                    payload.has("koreanName") shouldBe true
                    payload.get("koreanName").isNull shouldBe true
                }
            }
        }
    }
}
