package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodRecollectPageTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val namePrefix = "재수집화면-"

        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(
            rawName: String,
            contentStatus: FoodContentStatus = FoodContentStatus.READY,
            failureKind: FoodContentFailureKind? = null,
            reason: String? = null,
        ): Food = foodJpaRepository.save(
            Food(
                koreanName = namePrefix + rawName,
                displayName = namePrefix + rawName,
                description = "구수한 $rawName",
                spiciness = 1,
                contentStatus = contentStatus,
                contentFailureKind = failureKind,
                contentReviewRejectionReason = reason,
            ),
        )

        given("재수집 요청 화면") {
            `when`("검색 조건으로 재수집을 실행하면") {
                then("대기 요청이 쌓이고 결과가 목록으로 리다이렉트된다") {
                    clearFoods()
                    saveFood("칼국수")
                    saveFood("콩국수")

                    mockMvc.post("/admin/foods/recollect") {
                        cookie(adminCookie())
                        param("q", "국수")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrlPattern("/admin/foods/list?*recollected=2*")
                    }

                    outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING).size shouldBe 2
                }
            }

            `when`("조건에 걸린 음식이 없으면") {
                then("아무 요청도 쌓지 않고 안내로 돌아간다") {
                    clearFoods()
                    saveFood("비빔밥")

                    mockMvc.post("/admin/foods/recollect") {
                        cookie(adminCookie())
                        param("q", "국수")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrlPattern("/admin/foods/list?*recollectError=no-target*")
                    }

                    outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING).size shouldBe 0
                }
            }

            `when`("관리자 세션이 없으면") {
                then("실행되지 않는다") {
                    clearFoods()
                    saveFood("칼국수")

                    mockMvc.post("/admin/foods/recollect") { param("q", "국수") }
                        .andExpect { status { is3xxRedirection() } }

                    outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING).size shouldBe 0
                }
            }
        }

        given("실패 원인 확인") {
            `when`("실패 유형이 기록된 음식을 상세로 열면") {
                then("유형과 사유가 함께 보인다") {
                    clearFoods()
                    val food = saveFood(
                        "칼국수",
                        contentStatus = FoodContentStatus.FAILED,
                        failureKind = FoodContentFailureKind.INGREDIENT_GUARD,
                        reason = "기피성분 62점 < 임계값 80: 견과 교차오염 확인 필요",
                    )

                    val body = mockMvc.get("/admin/foods/list") {
                        cookie(adminCookie())
                        param("detail", food.id.toString())
                    }.andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    body shouldContain "INGREDIENT_GUARD"
                    body shouldContain "기피성분 62점"
                }
            }
        }
    }
}
