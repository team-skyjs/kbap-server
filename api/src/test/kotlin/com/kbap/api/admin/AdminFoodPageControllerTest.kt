package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodPageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearFoods() = foodJpaRepository.deleteAll()

        fun saveFood(koreanName: String, status: FoodContentStatus): Food =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = status),
            )

        beforeContainer { clearFoods() }

        given("음식 적재 현황 대시보드") {
            `when`("여러 준비 단계의 음식이 존재할 때 진입하면") {
                then("전체 건수·상태별 4종(0 채움)·READY 비율을 모델로 내려준다") {
                    saveFood("대시-미완료1", FoodContentStatus.INCOMPLETE)
                    saveFood("대시-미완료2", FoodContentStatus.INCOMPLETE)
                    saveFood("대시-검수", FoodContentStatus.PENDING_REVIEW)
                    saveFood("대시-레디", FoodContentStatus.READY)

                    mockMvc.get("/admin/foods") { cookie(adminCookie()) }.andExpect {
                        status { isOk() }
                        view { name("admin/foods") }
                        model {
                            attribute(
                                "dashboard",
                                AdminFoodDashboardView(
                                    total = 4,
                                    incomplete = 2,
                                    pendingImage = 0,
                                    pendingReview = 1,
                                    ready = 1,
                                    readyRatio = 25.0,
                                ),
                            )
                        }
                    }
                }
            }

            `when`("음식이 0건일 때 진입하면") {
                then("오류 없이 전체 0 건과 비율 0 을 내려준다") {
                    mockMvc.get("/admin/foods") { cookie(adminCookie()) }.andExpect {
                        status { isOk() }
                        view { name("admin/foods") }
                        model {
                            attribute(
                                "dashboard",
                                AdminFoodDashboardView(
                                    total = 0,
                                    incomplete = 0,
                                    pendingImage = 0,
                                    pendingReview = 0,
                                    ready = 0,
                                    readyRatio = 0.0,
                                ),
                            )
                        }
                    }
                }
            }

            `when`("미인증으로 진입하면") {
                then("로그인 화면으로 리다이렉트한다") {
                    mockMvc.get("/admin/foods").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/login")
                    }
                }
            }

            `when`("인증 상태로 홈에 접근하면") {
                then("대시보드로 리다이렉트한다") {
                    mockMvc.get("/admin") { cookie(adminCookie()) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods")
                    }
                }
            }
        }
    }
}
