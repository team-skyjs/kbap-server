package com.kbap.api.order

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.order.OrderJpaRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class OrderLocationEraseTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var orderRepository: OrderJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val memberId = 9571L

        fun seed() {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.executeUpdate(
                        "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) " +
                            "VALUES ($memberId, 'GOOGLE', 'erase-location-test', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE id = id",
                    )
                    it.executeUpdate("DELETE FROM orders WHERE member_id = $memberId")
                    it.executeUpdate(
                        "INSERT INTO orders (member_id, image_path, latitude, longitude, road_address, place_source, place_external_id, " +
                            "place_name, place_address, place_language) VALUES ($memberId, 'scan/erase/${System.nanoTime()}.jpg', 37.5, 127.0, " +
                            "'서울', 'GOOGLE_PLACE', 'ChIJerase', '식당', '주소', 'ko')",
                    )
                }
            }
        }

        fun snapshot(): List<String?> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT latitude, longitude, road_address, place_source, place_external_id, place_name, place_address, place_language, " +
                        "image_path, status FROM orders WHERE member_id = ?",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs -> rs.next(); (1..10).map { rs.getString(it) } }
                }
            }

        given("주문 위치 정보 파기") {
            `when`("같은 회원에 두 번 실행하면") {
                then("결과가 같다 — 위치 8컬럼은 NULL, 사진 경로·상태는 그대로") {
                    seed()
                    val transaction = TransactionTemplate(transactionManager)

                    transaction.execute { orderRepository.eraseLocationByMemberId(memberId) }
                    val once = snapshot()
                    transaction.execute { orderRepository.eraseLocationByMemberId(memberId) }

                    once.take(8) shouldBe List(8) { null }
                    once[8]!!.startsWith("scan/erase/") shouldBe true
                    once[9] shouldBe "ACTIVE"
                    snapshot() shouldBe once
                }
            }

            `when`("실행 계획을 보면") {
                then("member_id 인덱스(idx_orders_recent)로 그 회원의 행만 갱신한다 — 전체 스캔이 아니다") {
                    seed()
                    val plan = dataSource.connection.use { c ->
                        c.prepareStatement(
                            "EXPLAIN UPDATE orders SET latitude = NULL, longitude = NULL, road_address = NULL, place_source = NULL, " +
                                "place_external_id = NULL, place_name = NULL, place_address = NULL, place_language = NULL WHERE member_id = ?",
                        ).use { ps ->
                            ps.setLong(1, memberId)
                            ps.executeQuery().use { rs -> rs.next(); rs.getString("type") to rs.getString("key") }
                        }
                    }

                    plan.second shouldBe "idx_orders_recent"
                    (plan.first == "range" || plan.first == "ref") shouldBe true
                }
            }
        }
    }
}
