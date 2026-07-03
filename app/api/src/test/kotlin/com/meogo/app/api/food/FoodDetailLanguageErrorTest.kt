package com.meogo.app.api.food

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
class FoodDetailLanguageErrorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.seedDoenjangStew(dataSource) }

        given("음식 상세 조회 미지원 언어 코드 처리") {
            `when`("lang=fr 로 조회하면") {
                then("400 과 지원 목록을 포함한 실패 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "fr")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value(containsString("zh-Hans")) }
                        jsonPath("$.message") { value(containsString("es")) }
                    }
                }
            }

            `when`("lang=xx 로 조회하면") {
                then("400 과 실패 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "xx")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value(containsString("zh-Hans")) }
                    }
                }
            }

            `when`("lang=EN(대문자) 로 조회하면") {
                then("400 과 실패 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "된장찌개")
                        param("lang", "EN")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value(containsString("zh-Hans")) }
                    }
                }
            }
        }
    }
}
