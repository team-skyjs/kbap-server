package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AdminMemberControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var memberJpaRepository: MemberJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var reviewJpaRepository: ReviewJpaRepository

    @Autowired
    private lateinit var scanHistoryJpaRepository: ScanHistoryJpaRepository

    @Autowired
    private lateinit var orderJpaRepository: OrderJpaRepository

    @Autowired
    private lateinit var orderItemJpaRepository: OrderItemJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val path = "/api/admin/members"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getJson(target: String, token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get(target) { token?.let { header("Authorization", "Bearer $it") } }

        fun saveMember(uid: String, nickname: String? = null): Member =
            memberJpaRepository.save(
                Member(
                    provider = SocialProvider.GOOGLE,
                    providerUid = uid,
                    email = "$uid@test.com",
                    nickname = nickname,
                ),
            )

        fun saveFood(koreanName: String): Food =
            foodJpaRepository.save(Food(koreanName = koreanName, description = "구수한 $koreanName"))

        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("어드민 멤버 목록 조회 API") {
            `when`("여러 멤버가 있으면") {
                then("id 내림차순 목록과 전체 건수를 내려준다") {
                    saveMember("uid-1", nickname = "김밥러버")
                    val last = saveMember("uid-2", nickname = "떡볶이광")

                    getJson(path).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[0].id") { value(last.id) }
                        jsonPath("$.payload.items[0].nickname") { value("떡볶이광") }
                        jsonPath("$.payload.items[0].email") { value("uid-2@test.com") }
                        jsonPath("$.payload.items[0].provider") { value("GOOGLE") }
                        jsonPath("$.payload.items[0].memberStatus") { exists() }
                        jsonPath("$.payload.totalCount") { value(2) }
                        jsonPath("$.payload.page") { value(1) }
                    }
                }
            }

            `when`("검색어 q 로 닉네임을 찾으면") {
                then("부분 일치 건만 내려준다") {
                    saveMember("uid-1", nickname = "김밥러버")
                    saveMember("uid-2", nickname = "떡볶이광")

                    getJson("$path?q=김밥").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].nickname") { value("김밥러버") }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("검색어 q 로 이메일을 찾으면") {
                then("부분 일치 건만 내려준다") {
                    saveMember("alpha-user")
                    saveMember("beta-user")

                    getJson("$path?q=alpha").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].email") { value("alpha-user@test.com") }
                    }
                }
            }

            `when`("숫자 q 를 주면") {
                then("멤버 id 일치도 매칭한다") {
                    saveMember("uid-1")
                    val target = saveMember("uid-2")

                    getJson("$path?q=${target.id}").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(target.id) }
                    }
                }
            }

            `when`("검색어 q 에 LIKE 와일드카드가 있으면") {
                then("리터럴로만 매칭한다") {
                    saveMember("wild-a", nickname = "김치찌개러버")
                    saveMember("wild-b", nickname = "100%리얼러버")

                    mockMvc.get(path) {
                        header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
                        param("q", "%")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].nickname") { value("100%리얼러버") }
                    }
                }
            }

            `when`("탈퇴한 멤버가 있으면") {
                then("목록·검색에 WITHDRAWN 상태로 나온다") {
                    saveMember("stay-uid", nickname = "잔류회원")
                    val withdrawn = saveMember("gone-uid", nickname = "탈퇴회원")
                    memberJpaRepository.save(withdrawn.apply { withdraw() })

                    getJson(path).andExpect {
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[0].id") { value(withdrawn.id) }
                        jsonPath("$.payload.items[0].memberStatus") { value("WITHDRAWN") }
                        jsonPath("$.payload.items[1].memberStatus") { value("ACTIVE") }
                    }

                    getJson("$path?q=탈퇴").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].memberStatus") { value("WITHDRAWN") }
                    }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getJson(path, token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }

        given("어드민 멤버 상세 조회 API") {
            `when`("존재하는 멤버를 조회하면") {
                then("프로필·회피 설정·활동 수·제재 자리를 내려준다") {
                    val member = saveMember("detail-uid", nickname = "김밥러버")
                    orderJpaRepository.save(Order(memberId = member.id, imagePath = "orders/detail-1.webp"))

                    getJson("$path/${member.id}").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.id") { value(member.id) }
                        jsonPath("$.payload.nickname") { value("김밥러버") }
                        jsonPath("$.payload.email") { value("detail-uid@test.com") }
                        jsonPath("$.payload.provider") { value("GOOGLE") }
                        jsonPath("$.payload.onboardingCompleted") { value(false) }
                        jsonPath("$.payload.avoidanceSubstanceCodes") { exists() }
                        jsonPath("$.payload.spicinessPreference") { exists() }
                        jsonPath("$.payload.scanCount") { value(0) }
                        jsonPath("$.payload.reviewCount") { value(0) }
                        jsonPath("$.payload.orderCount") { value(1) }
                        jsonPath("$.payload.sanctions.length()") { value(0) }
                        jsonPath("$.payload.createdAt") { exists() }
                    }
                }
            }

            `when`("탈퇴한 멤버를 조회하면") {
                then("WITHDRAWN 상태로 상세와 활동을 그대로 볼 수 있다") {
                    val member = saveMember("withdrawn-uid", nickname = "탈퇴자")
                    memberJpaRepository.save(member.apply { withdraw() })

                    getJson("$path/${member.id}").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.memberStatus") { value("WITHDRAWN") }
                    }

                    getJson("$path/${member.id}/reviews").andExpect { status { isOk() } }
                }
            }

            `when`("없는 id 를 조회하면") {
                then("400(MEMBER-003) 로 거절한다") {
                    getJson("$path/999999").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("MEMBER-003") }
                    }
                }
            }
        }

        given("어드민 멤버 리뷰 목록 API") {
            `when`("해당 멤버의 리뷰가 있으면") {
                then("음식 이름과 함께 그 멤버 것만 내려준다") {
                    val member = saveMember("review-uid")
                    val other = saveMember("other-uid")
                    val food = saveFood("된장찌개")
                    reviewJpaRepository.save(Review(memberId = member.id, foodId = food.id, rating = 5, content = "굿"))
                    reviewJpaRepository.save(Review(memberId = other.id, foodId = food.id, rating = 1))

                    getJson("$path/${member.id}/reviews").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].foodId") { value(food.id) }
                        jsonPath("$.payload.items[0].foodName") { value("된장찌개") }
                        jsonPath("$.payload.items[0].rating") { value(5) }
                        jsonPath("$.payload.items[0].content") { value("굿") }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("없는 멤버의 리뷰를 조회하면") {
                then("400(MEMBER-003) 로 거절한다") {
                    getJson("$path/999999/reviews").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("MEMBER-003") }
                    }
                }
            }
        }

        given("어드민 멤버 스캔 목록 API") {
            `when`("음식 매칭 스캔과 미매칭 스캔이 있으면") {
                then("둘 다 내려주고 미매칭은 foodName null 이다") {
                    val member = saveMember("scan-uid")
                    val food = saveFood("김치찌개")
                    scanHistoryJpaRepository.save(ScanHistory.record(member.id, price = 9000, foodId = food.id))
                    scanHistoryJpaRepository.save(ScanHistory.record(member.id, price = null, foodId = null))

                    getJson("$path/${member.id}/scans").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.items[1].foodId") { value(food.id) }
                        jsonPath("$.payload.items[1].foodName") { value("김치찌개") }
                        jsonPath("$.payload.items[1].price") { value(9000) }
                        jsonPath("$.payload.items[0].foodName") { value(null) }
                        jsonPath("$.payload.totalCount") { value(2) }
                    }
                }
            }
        }

        given("어드민 멤버 주문 목록 API") {
            `when`("해당 멤버의 주문이 있으면") {
                then("메뉴판 이미지 URL·주소와 함께 내려준다") {
                    val member = saveMember("order-uid")
                    orderJpaRepository.save(
                        Order(memberId = member.id, imagePath = "orders/1.webp", roadAddress = "서울 마포구"),
                    )

                    getJson("$path/${member.id}/orders").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].imageUrl") { exists() }
                        jsonPath("$.payload.items[0].roadAddress") { value("서울 마포구") }
                        jsonPath("$.payload.items[0].createdAt") { exists() }
                        jsonPath("$.payload.items[0].items.length()") { value(0) }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("고가 항목 합계가 Int 범위를 넘으면") {
                then("Long 으로 정확히 집계한다") {
                    val member = saveMember("bigorder-uid")
                    val food = saveFood("금박한우")
                    val order = orderJpaRepository.save(Order(memberId = member.id, imagePath = "orders/big.webp"))
                    orderItemJpaRepository.save(
                        OrderItem.place(order.id, food.id, "금박한우", quantity = 2_000, price = 2_000_000),
                    )

                    getJson("$path/${member.id}/orders").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].totalPrice") { value(4_000_000_000L) }
                    }
                }
            }

            `when`("주문에 음식 항목이 있으면") {
                then("항목(주문 시점 이름·수량·가격)과 합계를 함께 내려준다") {
                    val member = saveMember("order-items-uid")
                    val food = saveFood("김치찌개")
                    val order = orderJpaRepository.save(
                        Order(memberId = member.id, imagePath = "orders/2.webp"),
                    )
                    orderItemJpaRepository.save(
                        OrderItem.place(order.id, food.id, "김치찌개 (2인분)", quantity = 2, price = 9000),
                    )
                    orderItemJpaRepository.save(
                        OrderItem.place(order.id, food.id, "공기밥", quantity = 1, price = null),
                    )

                    getJson("$path/${member.id}/orders").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].items.length()") { value(2) }
                        jsonPath("$.payload.items[0].items[0].foodId") { value(food.id) }
                        jsonPath("$.payload.items[0].items[0].menuName") { value("김치찌개 (2인분)") }
                        jsonPath("$.payload.items[0].items[0].quantity") { value(2) }
                        jsonPath("$.payload.items[0].items[0].price") { value(9000) }
                        jsonPath("$.payload.items[0].items[1].price") { value(null) }
                        jsonPath("$.payload.items[0].totalQuantity") { value(3) }
                        jsonPath("$.payload.items[0].totalPrice") { value(18000) }
                    }
                }
            }
        }
    }
}
