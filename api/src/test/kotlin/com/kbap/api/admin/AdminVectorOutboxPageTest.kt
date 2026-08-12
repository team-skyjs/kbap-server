package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
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
class AdminVectorOutboxPageTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val namePrefix = "벡터현황-"

        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clear(): Unit =
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFoodId(rawName: String): Long = foodRepository.save(Food.failed(namePrefix + rawName)).id

        fun renderedCount(body: String, outboxStatus: FoodVectorOutboxStatus): String? =
            Regex("""id="vector-outbox-count-$outboxStatus"[^>]*>([\d,]+)<""").find(body)?.groupValues?.get(1)

        fun saveFailedOutbox(rawName: String, lastError: String): FoodVectorOutbox {
            val food = foodRepository.save(Food.failed(namePrefix + rawName))
            val outbox = FoodVectorOutbox.upsert(food.id)
            repeat(FoodVectorOutbox.MAX_ATTEMPTS) { outbox.recordFailure(lastError) }
            return vectorOutboxRepository.save(outbox)
        }

        given("음식 대시보드의 벡터 동기화 현황") {
            `when`("실패한 동기화 건이 있으면") {
                then("대상 음식과 실패 원인·시도 횟수를 함께 보여준다") {
                    clear()
                    saveFailedOutbox("칼국수", "긴 설명이 비어 있습니다")
                    vectorOutboxRepository.save(
                        FoodVectorOutbox.upsert(foodRepository.save(Food.failed("${namePrefix}콩국수")).id),
                    )

                    val body = mockMvc.get("/admin/foods") { cookie(adminCookie()) }
                        .andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    body shouldContain "${namePrefix}칼국수"
                    body shouldContain "긴 설명이 비어 있습니다"
                }
            }

            `when`("대기·완료·실패 건이 섞여 있으면") {
                then("상태별 건수를 각각 보여준다") {
                    clear()
                    saveFailedOutbox("메밀국수", "문서 저장 실패")
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(saveFoodId("콩국수")))
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(saveFoodId("비빔국수")))
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(saveFoodId("잔치국수")).apply { complete() })

                    val body = mockMvc.get("/admin/foods") { cookie(adminCookie()) }
                        .andExpect { status { isOk() } }
                        .andReturn().response.contentAsString

                    renderedCount(body, FoodVectorOutboxStatus.PENDING) shouldBe "2"
                    renderedCount(body, FoodVectorOutboxStatus.COMPLETE) shouldBe "1"
                    renderedCount(body, FoodVectorOutboxStatus.FAILED) shouldBe "1"
                }
            }
        }

        given("실패 건 재처리") {
            `when`("관리자가 실패 건의 재처리를 지시하면") {
                then("대기 상태로 되돌리고 시도 횟수를 초기화한 뒤 대시보드로 돌려보낸다") {
                    clear()
                    val outbox = saveFailedOutbox("잔치국수", "문서 저장 실패")

                    mockMvc.post("/admin/foods/vector-outboxes/${outbox.id}/retry") { cookie(adminCookie()) }
                        .andExpect {
                            status { is3xxRedirection() }
                            redirectedUrl("/admin/foods")
                        }

                    val reloaded = vectorOutboxRepository.findById(outbox.id).orElseThrow()
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                    reloaded.attempts shouldBe 0
                    reloaded.lastError shouldBe "문서 저장 실패"
                }
            }

            `when`("실패 상태가 아닌 건에 재처리를 지시하면") {
                then("아무것도 바꾸지 않고 대시보드로 돌려보낸다") {
                    clear()
                    val food = foodRepository.save(Food.failed("${namePrefix}수제비"))
                    val outbox = vectorOutboxRepository.save(
                        FoodVectorOutbox.upsert(food.id).apply { recordFailure("일시 오류") },
                    )

                    mockMvc.post("/admin/foods/vector-outboxes/${outbox.id}/retry") { cookie(adminCookie()) }
                        .andExpect { status { is3xxRedirection() } }

                    val reloaded = vectorOutboxRepository.findById(outbox.id).orElseThrow()
                    reloaded.outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                    reloaded.attempts shouldBe 1
                }
            }
        }
    }
}
