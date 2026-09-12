package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AdminFoodOutboxDashboardTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var adminFoodOutboxQueryService: AdminFoodOutboxQueryService

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val namePrefix = "아웃박스현황-"

        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food_image")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveOutbox(rawName: String, sent: Boolean = false, complete: Boolean = false): FoodContentOutbox {
            val food = foodRepository.save(Food.failed(namePrefix + rawName))
            val outbox = FoodContentOutbox.pending(food.id, food.displayName)
            if (sent) outbox.markSent()
            val saved = outboxRepository.save(outbox)
            if (complete) {
                dataSource.connection.use { c ->
                    c.prepareStatement("UPDATE food_content_outbox SET outbox_status = 'COMPLETE' WHERE id = ?").use { ps ->
                        ps.setLong(1, saved.id)
                        ps.executeUpdate()
                    }
                }
            }
            return saved
        }

        given("수집 요청 현황 조회") {
            `when`("대기·발행됨·수집 완료 요청이 섞여 있으면") {
                then("세 상태의 건수와 최신순 목록을 담는다") {
                    clearFoods()
                    saveOutbox("칼국수")
                    saveOutbox("콩국수")
                    saveOutbox("잔치국수", sent = true)
                    saveOutbox("비빔국수", sent = true, complete = true)

                    val view = adminFoodOutboxQueryService.getOutboxDashboard()

                    view.pending shouldBe 2
                    view.sent shouldBe 1
                    view.complete shouldBe 1
                    view.recent.first().displayName shouldBe "${namePrefix}비빔국수"
                    view.recent.map { it.displayName } shouldBe listOf(
                        "${namePrefix}비빔국수", "${namePrefix}잔치국수", "${namePrefix}콩국수", "${namePrefix}칼국수",
                    )
                }
            }

            `when`("요청이 하나도 없으면") {
                then("0건과 빈 목록으로 성공한다") {
                    clearFoods()

                    val view = adminFoodOutboxQueryService.getOutboxDashboard()

                    view.pending shouldBe 0
                    view.sent shouldBe 0
                    view.complete shouldBe 0
                    view.recent shouldBe emptyList()
                }
            }
        }

        given("적재 현황 화면") {
            `when`("수집 요청이 있는 상태에서 열면") {
                then("수집 요청 섹션에 건수와 음식명이 보인다") {
                    clearFoods()
                    saveOutbox("칼국수")
                    saveOutbox("잔치국수", sent = true)

                    val body = mockMvc.get("/admin/foods") { cookie(adminCookie()) }
                        .andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    body shouldContain "수집 요청"
                    body shouldContain "${namePrefix}칼국수"
                    body shouldContain "${namePrefix}잔치국수"
                }
            }
        }
    }
}
