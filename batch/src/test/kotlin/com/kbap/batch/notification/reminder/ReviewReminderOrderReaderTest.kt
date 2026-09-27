package com.kbap.batch.notification.reminder

import com.kbap.batch.BatchIntegrationTest
import com.kbap.batch.notification.MutableClock
import com.kbap.batch.notification.nowInJvmZone
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.order.OrderItemJpaRepository
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp

@BatchIntegrationTest
class ReviewReminderOrderReaderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var orderRepository: OrderJpaRepository

    @Autowired
    private lateinit var orderItemRepository: OrderItemJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    private var seq = 0

    private fun order(memberId: Long, minutesAgo: Long): Order {
        val order = orderRepository.save(Order.create(memberId, "reader/${++seq}.jpg", null, null, null))
        orderItemRepository.save(OrderItem.place(order.id, 1L, "menu", 1, null))
        jdbcTemplate.update(
            "UPDATE orders SET created_at = ? WHERE id = ?",
            Timestamp.valueOf(clock.nowInJvmZone().minusMinutes(minutesAgo)),
            order.id,
        )
        return order
    }

    private fun readAll(reader: ReviewReminderOrderReader): List<Long> {
        reader.open(ExecutionContext())
        return generateSequence { reader.read() }.map { it.id }.toList()
    }

    init {
        given("리뷰 리마인더 대상 주문 리더") {
            `when`("창 안 주문 250건과 창 밖 주문이 섞여 있으면") {
                orderItemRepository.deleteAll()
                orderRepository.deleteAll()
                notificationRepository.deleteAll()
                clock.setSeoul(2026, 9, 20, 12, 0)
                val inWindow = (1..250).map { order(2000L + it, 70).id }
                order(3001L, 30)
                order(3002L, 26 * 60)
                val reader = ReviewReminderOrderReader(orderRepository, clock, 100)

                then("창 안 주문만 id 오름차순으로 끝까지 읽는다") {
                    readAll(reader) shouldBe inWindow
                }
                then("다시 열면 처음부터 읽는다") {
                    readAll(reader) shouldBe inWindow
                }
            }

            `when`("대상이 없으면") {
                orderItemRepository.deleteAll()
                orderRepository.deleteAll()

                then("바로 끝난다") {
                    readAll(ReviewReminderOrderReader(orderRepository, clock, 100)) shouldBe emptyList()
                }
            }
        }
    }
}
