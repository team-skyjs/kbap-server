package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodCatalogControllerTest : BehaviorSpec() {
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
        val path = "/api/admin/foods"
        val spaOrigin = "https://kbap-admin.pages.dev"

        fun clearFoods() {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(koreanName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = contentStatus),
            )

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getList(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun preflight(target: String, origin: String): ResultActionsDsl =
            mockMvc.options(target) {
                header("Origin", origin)
                header("Access-Control-Request-Method", "GET")
                header("Access-Control-Request-Headers", "authorization,x-api-version")
            }

        beforeContainer { clearFoods() }
        afterSpec { clearFoods() }

        given("어드민 음식 목록 조회 API") {
            `when`("음식 여러 건이 있으면") {
                then("id 내림차순 목록과 전체 건수를 내려준다") {
                    saveFood("김치찌개")
                    saveFood("된장찌개")
                    val last = saveFood("비빔밥")

                    getList().andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(3) }
                        jsonPath("$.payload.items[0].id") { value(last.id) }
                        jsonPath("$.payload.items[0].koreanName") { value("비빔밥") }
                        jsonPath("$.payload.items[0].contentStatus") { value("READY") }
                        jsonPath("$.payload.totalCount") { value(3) }
                        jsonPath("$.payload.totalPages") { value(1) }
                        jsonPath("$.payload.page") { value(1) }
                        jsonPath("$.payload.hasPrev") { value(false) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("검색어 q 를 주면") {
                then("표시 이름 부분 일치 건만 전체 건수와 함께 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    getList("?q=김치").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.totalCount") { value(2) }
                    }
                }
            }

            `when`("status 필터를 주면") {
                then("해당 콘텐츠 상태 건만 내려준다") {
                    saveFood("김치찌개")
                    val pending = saveFood("된장찌개", FoodContentStatus.PENDING_REVIEW)

                    getList("?status=PENDING_REVIEW").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(pending.id) }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("q 와 status 를 함께 주면") {
                then("두 조건을 모두 만족하는 건만 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥", FoodContentStatus.PENDING_REVIEW)
                    saveFood("된장찌개", FoodContentStatus.PENDING_REVIEW)

                    getList("?q=김치&status=PENDING_REVIEW").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("김치볶음밥") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 로 거절한다") {
                    getList(token = null).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getList(token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }

        given("어드민 SPA CORS") {
            `when`("허용 오리진에서 Authorization 없이 프리플라이트를 보내면") {
                then("허용 오리진을 에코해 응답한다") {
                    preflight(path, spaOrigin).andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("pages.dev 프리뷰 서브도메인 오리진이면") {
                then("와일드카드 패턴으로 허용한다") {
                    val previewOrigin = "https://abc123.kbap-admin.pages.dev"
                    preflight(path, previewOrigin).andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", previewOrigin) }
                    }
                }
            }

            `when`("로컬 dev 오리진이면") {
                then("허용한다") {
                    preflight(path, "http://localhost:5173").andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
                    }
                }
            }

            `when`("허용 오리진에서 토큰 없이 실제 요청을 보내면") {
                then("401 응답에도 CORS 헤더가 중복 없이 실린다") {
                    mockMvc.get(path) { header("Origin", spaOrigin) }.andExpect {
                        status { isUnauthorized() }
                        header { stringValues("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("허용 오리진에서 위조된 토큰으로 실제 요청을 보내면") {
                then("401 응답에도 CORS 헤더가 실린다") {
                    mockMvc.get(path) {
                        header("Origin", spaOrigin)
                        header("Authorization", "Bearer forged-token")
                    }.andExpect {
                        status { isUnauthorized() }
                        header { stringValues("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("허용 목록 밖 오리진에서 어드민 API 프리플라이트를 보내면") {
                then("거절한다") {
                    preflight(path, "https://evil.example.com").andExpect {
                        status { isForbidden() }
                    }
                }
            }

            `when`("허용 목록 밖 오리진에서 유효한 관리자 토큰으로 실제 요청을 보내면") {
                then("CORS 에서 거절한다") {
                    mockMvc.get(path) {
                        header("Origin", "https://evil.example.com")
                        header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
                    }.andExpect { status { isForbidden() } }
                }
            }

            `when`("어드민이 아닌 앱 API 경로에 임의 오리진으로 프리플라이트를 보내면") {
                then("기존 전역 CORS 가 그대로 허용한다") {
                    preflight("/api/orders", "https://anywhere.example.com").andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", "https://anywhere.example.com") }
                    }
                }
            }
        }
    }
}
