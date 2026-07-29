package com.kbap.api.admin

import com.kbap.api.food.FakeFoodImageBatchClient
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import io.kotest.matchers.shouldBe
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

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

    @Autowired
    private lateinit var fakeClient: FakeFoodImageBatchClient

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearFoods() {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(koreanName: String, status: FoodContentStatus): Food =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = status),
            )

        beforeContainer {
            clearFoods()
            fakeClient.reset()
        }
        afterSpec { clearFoods() }

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

        given("화면에서 음식 시드 등록") {
            `when`("등록 화면에 진입하면") {
                then("시드 입력 화면을 보여준다") {
                    mockMvc.get("/admin/foods/seed") { cookie(adminCookie()) }.andExpect {
                        status { isOk() }
                        view { name("admin/food-seed") }
                    }
                }
            }

            `when`("줄 단위 이름(공백 줄 포함)을 제출하면") {
                then("등록 건수를 query parameter 로 담아 대시보드로 리다이렉트한다") {
                    mockMvc.post("/admin/foods/seed") {
                        cookie(adminCookie())
                        param("koreanNames", "폼시드마라탕\n\n  \n폼시드분짜")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/seed?seeded=2&skipped=0")
                    }

                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("빈 입력을 제출하면") {
                then("오류 파라미터로 리다이렉트하고 데이터를 변경하지 않는다") {
                    mockMvc.post("/admin/foods/seed") {
                        cookie(adminCookie())
                        param("koreanNames", "\n   \n")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/seed?error=empty-seed")
                    }

                    foodJpaRepository.count() shouldBe 0
                }
            }

            `when`("정규화하면 남는 이름이 없는 입력(비한글)을 제출하면") {
                then("0건 성공이 아니라 오류 파라미터로 리다이렉트한다") {
                    mockMvc.post("/admin/foods/seed") {
                        cookie(adminCookie())
                        param("koreanNames", "abc\n123")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/seed?error=no-valid-names")
                    }

                    foodJpaRepository.count() shouldBe 0
                }
            }

            `when`("500건을 넘는 목록을 제출하면") {
                then("REST 와 동일하게 거절한다 — 검증 경계 우회 금지") {
                    val bulk = (1..501).joinToString("\n") { "대량폼메뉴$it" }

                    mockMvc.post("/admin/foods/seed") {
                        cookie(adminCookie())
                        param("koreanNames", bulk)
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/seed?error=too-many-names")
                    }

                    foodJpaRepository.count() shouldBe 0
                }
            }

            `when`("255자를 넘는 이름이 섞여 있으면") {
                then("거절하고 데이터를 변경하지 않는다") {
                    mockMvc.post("/admin/foods/seed") {
                        cookie(adminCookie())
                        param("koreanNames", "정상김치찌개\n${"가".repeat(256)}")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/seed?error=name-too-long")
                    }

                    foodJpaRepository.count() shouldBe 0
                }
            }
        }

        given("화면에서 이미지 배치 제출") {
            `when`("이미지 없는 음식이 있을 때 제출하면") {
                then("제출 건수를 담아 리다이렉트하고 배치 처리 목록에 노출된다") {
                    saveFood("이미지폼-마라탕", FoodContentStatus.INCOMPLETE)

                    mockMvc.post("/admin/foods/images") { cookie(adminCookie()) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/images?submittedFoods=1&submittedBatches=1")
                    }

                    fakeClient.submitted.size shouldBe 1

                    val result = mockMvc.get("/admin/foods/images") { cookie(adminCookie()) }.andExpect {
                        status { isOk() }
                        view { name("admin/food-images") }
                    }.andReturn()

                    @Suppress("UNCHECKED_CAST")
                    val batches = result.modelAndView!!.model["batches"] as List<AdminImageBatchView>
                    batches.size shouldBe 1
                    batches.first().batchStatus shouldBe ImageBatchStatus.SUBMITTED
                    batches.first().pendingCount shouldBe 1
                    batches.first().totalCount shouldBe 1
                }
            }

            `when`("배치가 없을 때 처리 화면에 진입하면") {
                then("빈 목록으로 화면을 보여준다") {
                    val result = mockMvc.get("/admin/foods/images") { cookie(adminCookie()) }.andExpect {
                        status { isOk() }
                        view { name("admin/food-images") }
                    }.andReturn()

                    @Suppress("UNCHECKED_CAST")
                    val batches = result.modelAndView!!.model["batches"] as List<AdminImageBatchView>
                    batches shouldBe emptyList()
                }
            }

            `when`("대상이 0건이면") {
                then("오류가 아닌 0건 결과로 리다이렉트한다") {
                    mockMvc.post("/admin/foods/images") { cookie(adminCookie()) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/images?submittedFoods=0&submittedBatches=0")
                    }
                }
            }

            `when`("제출 처리 중 예외가 나면") {
                then("JSON 을 노출하지 않고 오류 파라미터로 리다이렉트한다") {
                    saveFood("이미지폼-실패탕", FoodContentStatus.INCOMPLETE)
                    fakeClient.submitFailure = RuntimeException("openai 다운")

                    mockMvc.post("/admin/foods/images") { cookie(adminCookie()) }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/images?error=images-failed")
                    }
                }
            }
        }
    }
}
