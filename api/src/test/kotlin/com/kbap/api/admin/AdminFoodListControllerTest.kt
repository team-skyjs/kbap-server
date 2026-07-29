package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

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

        fun saveFood(koreanName: String): Food =
            foodJpaRepository.save(Food(koreanName = koreanName, description = "구수한 $koreanName"))

        fun listPageOf(result: MvcResult): AdminFoodListPageView =
            result.modelAndView!!.model["foodPage"] as AdminFoodListPageView

        fun getList(query: String = ""): MvcResult =
            mockMvc.get("/admin/foods/list$query") { cookie(adminCookie()) }
                .andExpect {
                    status { isOk() }
                    view { name("admin/food-list") }
                }.andReturn()

        beforeContainer { clearFoods() }
        afterSpec { clearFoods() }

        given("음식 목록 offset 페이징") {
            `when`("음식 201건에서 첫 페이지를 조회하면") {
                then("id 내림차순 200건과 페이지 정보를 내려준다") {
                    foodJpaRepository.saveAll((1..201).map { Food(koreanName = "목록음식$it", description = "구수한 목록음식$it") })

                    val page = listPageOf(getList())

                    page.items.size shouldBe 200
                    page.page shouldBe 1
                    page.totalPages shouldBe 2
                    page.totalCount shouldBe 201
                    page.hasNext shouldBe true

                    val page2 = listPageOf(getList("?page=2"))
                    page2.items.size shouldBe 1
                    page2.hasPrev shouldBe true
                }
            }

            `when`("음식이 없으면") {
                then("빈 목록을 내려준다") {
                    listPageOf(getList()).items shouldBe emptyList()
                }
            }
        }

        given("음식 상세 모달") {
            `when`("detail 파라미터로 조회하면") {
                then("전 컬럼 데이터를 모델로 내려준다") {
                    val saved = foodJpaRepository.save(
                        Food(
                            koreanName = "모달비빔밥",
                            description = "모달 설명",
                            spiciness = 2,
                            contentStatus = FoodContentStatus.READY,
                            nameTranslations = mapOf("en" to "Bibimbap"),
                        ),
                    )

                    val result = getList("?detail=${saved.id}")

                    val detail = result.modelAndView!!.model["foodDetail"] as AdminFoodDetailView
                    detail.shouldNotBeNull()
                    detail.id shouldBe saved.id
                    detail.koreanName shouldBe "모달비빔밥"
                    detail.spiciness shouldBe 2
                    detail.contentStatus shouldBe FoodContentStatus.READY
                    detail.nameTranslationsJson shouldContain "Bibimbap"
                }
            }

            `when`("존재하지 않는 detail id 면") {
                then("모달 없이 목록만 보여준다") {
                    getList("?detail=999999").modelAndView!!.model["foodDetail"] shouldBe null
                }
            }
        }

        given("음식 컬럼 수정") {
            `when`("모달 폼에서 전 컬럼을 수정 제출하면") {
                then("반영하고 목록으로 리다이렉트한다") {
                    val saved = saveFood("수정전이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "수정후이름")
                        param("description", "수정된 설명")
                        param("spiciness", "3")
                        param("contentStatus", "PENDING_REVIEW")
                        param("imageRef", "food/1.png")
                        param("nameTranslationsJson", """{"en":"Edited"}""")
                        param("descriptionTranslationsJson", """{"en":"Edited desc"}""")
                        param("avoidanceSubstancesJson", """[{"code":"PORK","inclusion_percent":80}]""")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&updated=${saved.id}")
                    }

                    val updated = foodJpaRepository.findById(saved.id).get()
                    updated.koreanName shouldBe "수정후이름"
                    updated.description shouldBe "수정된 설명"
                    updated.spiciness shouldBe 3
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    updated.imageRef shouldBe "food/1.png"
                    updated.nameTranslations shouldBe mapOf("en" to "Edited")
                    updated.avoidanceSubstances!!.single().code shouldBe "PORK"
                }
            }

            `when`("빈 avoidanceSubstancesJson 과 빈 imageRef 로 제출하면") {
                then("각각 미조사(null)로 반영된다") {
                    val saved = saveFood("널수정이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "널수정이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "INCOMPLETE")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{}")
                        param("descriptionTranslationsJson", "{}")
                        param("avoidanceSubstancesJson", "")
                    }.andExpect { status { is3xxRedirection() } }

                    val updated = foodJpaRepository.findById(saved.id).get()
                    updated.imageRef shouldBe null
                    updated.avoidanceSubstances shouldBe null
                }
            }

            `when`("잘못된 JSON 으로 제출하면") {
                then("오류 파라미터와 함께 모달을 다시 열고 데이터를 바꾸지 않는다") {
                    val saved = saveFood("JSON오류이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "JSON오류이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "INCOMPLETE")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{잘못된}")
                        param("descriptionTranslationsJson", "{}")
                        param("avoidanceSubstancesJson", "")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&detail=${saved.id}&error=invalid-json")
                    }

                    foodJpaRepository.findById(saved.id).get().koreanName shouldBe "JSON오류이름"
                }
            }

            `when`("다른 음식과 중복되는 이름으로 수정하면") {
                then("중복 오류 파라미터로 리다이렉트한다") {
                    saveFood("중복대상이름")
                    val saved = saveFood("중복시도이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "중복대상이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "INCOMPLETE")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{}")
                        param("descriptionTranslationsJson", "{}")
                        param("avoidanceSubstancesJson", "")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&detail=${saved.id}&error=duplicate-name")
                    }
                }
            }
        }
    }
}
