package com.kbap.app.api.food
import com.kbap.common.core.testsupport.MySqlContainerConfig
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
class FoodDetailErrorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        beforeTest { FoodTestSeed.clear(dataSource) }

        given("음식 상세 조회 오류 처리") {
            `when`("존재하지 않는 foodId 로 조회하면") {
                then("400 과 '해당 음식 정보를 찾을 수 없습니다' 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/999999?lang=ko").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("해당 음식 정보를 찾을 수 없습니다") }
                    }
                }
            }

            `when`("소프트삭제된 음식의 foodId 로 조회하면") {
                then("400 과 '해당 음식 정보를 찾을 수 없습니다' 메시지를 반환한다") {
                    FoodTestSeed.seedDeletedFood(dataSource)

                    mockMvc.get("/api/v1/foods/${FoodTestSeed.DELETED_FOOD_ID}?lang=ko").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("해당 음식 정보를 찾을 수 없습니다") }
                    }
                }
            }

            `when`("숫자가 아닌 foodId 로 조회하면") {
                then("400 과 '잘못된 요청입니다' 메시지를 반환한다") {
                    mockMvc.get("/api/v1/foods/abc?lang=ko").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.message") { value("잘못된 요청입니다") }
                    }
                }
            }
        }
    }
}
