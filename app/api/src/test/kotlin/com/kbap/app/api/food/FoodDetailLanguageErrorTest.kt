package com.kbap.app.api.food
import com.kbap.common.core.testsupport.MySqlContainerConfig
import org.springframework.context.annotation.Import

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
@Import(MySqlContainerConfig::class)
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
                then("400 이 아니라 200 과 영어 응답을 반환한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "fr")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                    }
                }
            }

            `when`("lang=EN(대문자) 로 조회하면") {
                then("정확 일치가 아니므로 영어로 폴백한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "EN")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                    }
                }
            }

            `when`("lang=ko-KR(지역 태그) 로 조회하면") {
                then("정확 일치가 아니므로 영어로 폴백한다") {
                    mockMvc.get("/api/v1/foods/1") {
                        param("lang", "ko-KR")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.name") { value("Doenjang Stew") }
                    }
                }
            }
        }
    }
}
