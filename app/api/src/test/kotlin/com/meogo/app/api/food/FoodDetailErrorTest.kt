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
class FoodDetailErrorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.clear(dataSource) }

        given("음식 상세 조회 오류 처리") {
            `when`("수록되지 않은 메뉴명으로 조회하면") {
                then("400 과 '해당 음식 정보 없음' 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "존재하지않는메뉴")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("해당 음식 정보 없음") }
                    }
                }
            }

            `when`("menuName 이 blank 이면") {
                then("400 과 'menuName은 필수입니다' 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/detail") {
                        param("menuName", "   ")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("menuName은 필수입니다") }
                    }
                }
            }
        }
    }
}
