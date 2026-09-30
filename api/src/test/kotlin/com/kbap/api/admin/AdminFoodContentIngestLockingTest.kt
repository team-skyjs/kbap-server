package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.PATH
import com.kbap.api.admin.AdminFoodContentIngestTestSupport.failedBody
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@IntegrationTest
class AdminFoodContentIngestLockingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var foodJpaRepository: FoodJpaRepository
    @Autowired private lateinit var outboxRepository: FoodContentOutboxJpaRepository
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var ingestService: AdminFoodContentIngestService
    @Autowired private lateinit var adminFoodService: AdminFoodService

    private val mapper = jacksonObjectMapper()

    init {
        fun saveSent(name: String): Pair<Food, FoodContentOutbox> {
            val food = foodJpaRepository.save(Food.failed(name))
            val outbox = outboxRepository.save(
                FoodContentOutbox.pending(food.id, food.displayName).apply {
                    outboxStatus = FoodContentOutboxStatus.SENT
                    sentAt = LocalDateTime.now().minusHours(30)
                    attempts = 1
                },
            )
            return food to outbox
        }

        fun callback(foodId: Long, outboxId: Long): Int =
            mockMvc.post(PATH) {
                header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(failedBody(foodId, outboxId))
            }.andReturn().response.status

        fun outboxLockCount(): Int {
            val url = dataSource.connection.use { it.metaData.url }
            return DriverManager.getConnection(url, "root", MySqlContainerConfig.PASSWORD).use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(
                        "SELECT COUNT(*) FROM performance_schema.data_locks " +
                            "WHERE OBJECT_SCHEMA = DATABASE() AND OBJECT_NAME = 'food_content_outbox'",
                    ).use { rs -> rs.next(); rs.getInt(1) }
                }
            }
        }

        fun statusOf(outboxId: Long): FoodContentOutboxStatus = outboxRepository.findById(outboxId).orElseThrow().outboxStatus

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("서로 다른 음식의 콜백이 동시에 몰림") {
            `when`("아웃박스 100행 중 20건의 콜백이 한꺼번에 오면") {
                then("교착 없이 전부 200 이고 전부 완료된다 — 콜백은 자기 음식·자기 아웃박스 행만 잠근다") {
                    val targets = (1..100).map { saveSent("동시콜백음식$it") }.take(20)
                    val gate = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(targets.size)
                    val statuses = targets.map { (food, outbox) -> executor.submit<Int> { gate.await(); callback(food.id, outbox.id) } }

                    gate.countDown()

                    statuses.map { it.get() } shouldBe List(targets.size) { 200 }
                    executor.shutdown()
                    targets.map { statusOf(it.second.id) }.toSet() shouldBe setOf(FoodContentOutboxStatus.COMPLETE)
                }
            }
        }

        given("콜백 한 건이 쥐는 아웃박스 잠금") {
            `when`("아웃박스가 100행일 때 콜백 트랜잭션이 끝나기 전에 잠금을 세면") {
                then("테이블 행 수와 무관한 상수(≤3)다 — 완료 UPDATE 가 남의 행을 읽지 않는다") {
                    val (food, outbox) = (1..100).map { saveSent("잠금계측음식$it") }[50]

                    val locks = TransactionTemplate(transactionManager).execute {
                        ingestService.ingestFailure(outbox.id, food.id, FoodContentFailureKind.JUDGE_REJECTED, "잠금 계측")
                        outboxLockCount()
                    }!!

                    locks shouldBeGreaterThanOrEqual 1
                    locks shouldBeLessThanOrEqual 3
                    statusOf(outbox.id) shouldBe FoodContentOutboxStatus.COMPLETE
                }
            }
        }

        given("콜백의 대체 판정 = 회수의 NOT_SUPERSEDED") {
            `when`("더 새 요청이 삭제(비ACTIVE)된 음식과 더 새 요청이 살아 있는 음식에 옛 요청의 콜백이 오면") {
                then("두 경로가 같은 답을 낸다 — 삭제된 새 요청은 대체가 아니라 옛 결과를 반영하고(회수 대상이기도 하다), 살아 있는 새 요청은 대체라 버린다(회수 대상도 아니다)") {
                    val before = LocalDateTime.now().minusHours(24)
                    val (cancelledFood, cancelledOld) = saveSent("취소된재수집음식")
                    outboxRepository.save(FoodContentOutbox.pending(cancelledFood.id, cancelledFood.displayName).apply { delete() })
                    val (liveFood, liveOld) = saveSent("살아있는재수집음식")
                    outboxRepository.save(FoodContentOutbox.pending(liveFood.id, liveFood.displayName))
                    val attemptsBefore = foodJpaRepository.findById(cancelledFood.id).orElseThrow().contentReviewAttempts

                    outboxRepository.countStillStale(cancelledOld.id, before) shouldBe 1
                    outboxRepository.countStillStale(liveOld.id, before) shouldBe 0

                    callback(cancelledFood.id, cancelledOld.id) shouldBe 200
                    callback(liveFood.id, liveOld.id) shouldBe 200

                    statusOf(cancelledOld.id) shouldBe FoodContentOutboxStatus.COMPLETE
                    foodJpaRepository.findById(cancelledFood.id).orElseThrow().contentReviewAttempts shouldBe attemptsBefore + 1
                    statusOf(liveOld.id) shouldBe FoodContentOutboxStatus.SENT
                }
            }
        }

        given("콜백과 다른 경로의 교차 — 잠금 순서는 어디서나 food 행 → 아웃박스 행") {
            `when`("재수집 요청이 음식 행을 잠그고 새 요청을 넣는 동안 옛 요청의 콜백이 오면") {
                then("콜백은 그 뒤로 직렬화돼 새 요청을 보고 옛 결과를 버린다 — 교착 없음, 옛 행은 완료되지 않는다") {
                    val (food, old) = saveSent("재수집교차음식")
                    val attemptsBefore = foodJpaRepository.findById(food.id).orElseThrow().contentReviewAttempts
                    val locked = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val recollect = executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            adminFoodService.requestRecollectForFood(food.id)
                            locked.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    locked.await()

                    val started = System.nanoTime()
                    callback(food.id, old.id) shouldBe 200
                    Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThan 1_000L
                    recollect.get()
                    executor.shutdown()

                    statusOf(old.id) shouldBe FoodContentOutboxStatus.SENT
                    outboxRepository.findByFoodIdInAndOutboxStatus(listOf(food.id), FoodContentOutboxStatus.PENDING).size shouldBe 1
                    foodJpaRepository.findById(food.id).orElseThrow().contentReviewAttempts shouldBe attemptsBefore
                }
            }

            `when`("회수 잡이 음식 행을 잠그고 굳은 요청을 대기로 되돌리는 동안 콜백이 오면") {
                then("콜백은 그 뒤로 직렬화돼 되살아난 요청을 완료한다 — 교착 없음") {
                    val (food, outbox) = saveSent("회수교차음식")
                    val locked = CountDownLatch(1)
                    val executor = Executors.newSingleThreadExecutor()
                    val recovery = executor.submit {
                        TransactionTemplate(transactionManager).execute {
                            foodJpaRepository.findByIdForUpdate(food.id)
                            outboxRepository.requeueIfStillStale(outbox.id, LocalDateTime.now(), "회수 교차")
                            locked.countDown()
                            Thread.sleep(1_500)
                        }
                    }
                    locked.await()

                    val started = System.nanoTime()
                    callback(food.id, outbox.id) shouldBe 200
                    Duration.ofNanos(System.nanoTime() - started).toMillis() shouldBeGreaterThan 1_000L
                    recovery.get()
                    executor.shutdown()

                    statusOf(outbox.id) shouldBe FoodContentOutboxStatus.COMPLETE
                }
            }
        }
    }
}
