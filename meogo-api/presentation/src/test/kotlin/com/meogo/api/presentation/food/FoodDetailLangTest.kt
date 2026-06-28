package com.meogo.api.presentation.food

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

            `when`("지원하지 않는 lang=xx 로 조회하면") {
                then("ko 로 폴백해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "xx")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }

            `when`("lang 을 지정하지 않으면") {
                then("ko 로 폴백해 한국어 음식명을 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("된장찌개") }
                    }
                }
            }
        }
    }
}
