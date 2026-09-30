package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.failedBody
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class FoodContentInFlightRequestMatrixTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var outboxRepository: FoodContentOutboxJpaRepository
    @Autowired private lateinit var adminFoodService: AdminFoodService
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var dataSource: DataSource

    private val mapper = jacksonObjectMapper()

    private enum class RowState { PENDING, SENT, SENT_DEAD, COMPLETE }

    private enum class Callback { COMPLETES, DROPPED, REJECTED }

    private data class Expectation(
        val published: Boolean,
        val recovered: Boolean,
        val conflicts: Boolean,
        val callback: Callback,
        val recollectSkipped: Boolean,
    )

    init {
        fun token(): String = tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)

        fun row(food: Food, state: RowState): FoodContentOutbox =
            outboxRepository.save(
                FoodContentOutbox.pending(food.id, food.displayName).apply {
                    when (state) {
                        RowState.PENDING -> Unit
                        RowState.SENT, RowState.SENT_DEAD -> {
                            outboxStatus = FoodContentOutboxStatus.SENT
                            sentAt = LocalDateTime.now().minusHours(30)
                            attempts = 1
                            if (state == RowState.SENT_DEAD) markDead("테스트 포기")
                        }
                        RowState.COMPLETE -> outboxStatus = FoodContentOutboxStatus.COMPLETE
                    }
                },
            )

        fun fixture(state: RowState, superseded: Boolean): Pair<Food, FoodContentOutbox> {
            val food = foodRepository.save(
                Food(koreanName = "상태표음식", description = "설명", imageRef = "images/webp/matrix.webp", contentStatus = FoodContentStatus.READY),
            )
            val target = row(food, state)
            if (superseded) row(food, RowState.COMPLETE)
            return food to target
        }

        fun isPublished(outbox: FoodContentOutbox): Boolean =
            outboxRepository.findPendingAfterId(0, 100).any { it.id == outbox.id }

        fun isRecovered(outbox: FoodContentOutbox): Boolean =
            outboxRepository.countStillStale(outbox.id, LocalDateTime.now().minusHours(24)) == 1L

        fun regenerationConflicts(food: Food): Boolean {
            val response = mockMvc.post("/api/admin/foods/${food.id}/regenerate-image") {
                header("Authorization", "Bearer ${token()}")
            }.andReturn().response
            if (response.status == 200) return false
            mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("code").asText() shouldBe "FOOD-020"
            return true
        }

        fun callbackOutcome(food: Food, outbox: FoodContentOutbox): Callback {
            val before = outbox.outboxStatus
            val status = mockMvc.post(PATH) {
                header("Authorization", "Bearer ${token()}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(failedBody(food.id, outbox.id))
            }.andReturn().response.status
            val after = outboxRepository.findById(outbox.id).orElseThrow().outboxStatus
            return when {
                status != 200 -> Callback.REJECTED.also { after shouldBe before }
                after == FoodContentOutboxStatus.COMPLETE && before != FoodContentOutboxStatus.COMPLETE -> Callback.COMPLETES
                else -> Callback.DROPPED.also { after shouldBe before }
            }
        }

        fun recollectSkipped(food: Food): Boolean = adminFoodService.requestRecollectForFood(food.id).created == 0L

        val notInFlight = Expectation(published = false, recovered = false, conflicts = false, callback = Callback.DROPPED, recollectSkipped = false)
        val expectations = mapOf(
            (RowState.PENDING to false) to Expectation(published = true, recovered = false, conflicts = true, callback = Callback.COMPLETES, recollectSkipped = true),
            (RowState.SENT to false) to Expectation(published = false, recovered = true, conflicts = true, callback = Callback.COMPLETES, recollectSkipped = false),
            (RowState.SENT_DEAD to false) to notInFlight,
            (RowState.COMPLETE to false) to notInFlight.copy(callback = Callback.REJECTED),
            (RowState.PENDING to true) to notInFlight,
            (RowState.SENT to true) to notInFlight,
            (RowState.SENT_DEAD to true) to notInFlight,
            (RowState.COMPLETE to true) to notInFlight,
        )

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("진행 중 요청의 정의 하나 — 그 음식의 최신 행이면서 PENDING 또는 (SENT 이고 포기 아님)") {
            expectations.forEach { (cell, expected) ->
                val (state, superseded) = cell
                val label = "$state·${if (superseded) "대체됨" else "최신"}"
                `when`("아웃박스 행이 $label 이면") {
                    then("$label — 발행 대상 ${expected.published}") {
                        val (_, outbox) = fixture(state, superseded)
                        isPublished(outbox) shouldBe expected.published
                    }
                    then("$label — 회수 대상 ${expected.recovered}") {
                        val (_, outbox) = fixture(state, superseded)
                        isRecovered(outbox) shouldBe expected.recovered
                    }
                    then("$label — 이미지 재생성 충돌 ${expected.conflicts}") {
                        val (food, _) = fixture(state, superseded)
                        regenerationConflicts(food) shouldBe expected.conflicts
                    }
                    then("$label — 콜백 ${expected.callback}") {
                        val (food, outbox) = fixture(state, superseded)
                        callbackOutcome(food, outbox) shouldBe expected.callback
                    }
                    then("$label — 재수집 건너뜀 ${expected.recollectSkipped}") {
                        val (food, _) = fixture(state, superseded)
                        recollectSkipped(food) shouldBe expected.recollectSkipped
                    }
                }
            }
        }
    }
}
