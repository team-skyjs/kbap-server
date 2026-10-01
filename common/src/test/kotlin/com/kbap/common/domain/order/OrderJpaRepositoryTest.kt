package com.kbap.common.domain.order

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.domain.order.model.Order
import com.kbap.common.domain.order.model.OrderItem
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.domain.review.model.Review
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class OrderJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var orderRepository: OrderJpaRepository

    @Autowired
    private lateinit var orderItemRepository: OrderItemJpaRepository

    @Autowired
    private lateinit var reviewRepository: ReviewJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        val now = LocalDateTime.of(2026, 9, 28, 12, 0)
        val from = now.minusHours(25)
        val to = now.minusHours(1)
        var seq = 0

        fun clear() {
            orderItemRepository.deleteAll()
            orderRepository.deleteAll()
            reviewRepository.deleteAll()
            notificationRepository.deleteAll()
        }

        fun stamp(table: String, id: Long, createdAt: LocalDateTime) {
            jdbcTemplate.update("UPDATE $table SET created_at = ? WHERE id = ?", Timestamp.valueOf(createdAt), id)
        }

        fun order(memberId: Long, minutesAgo: Long, foodIds: List<Long> = listOf(1L, 2L)): Order {
            val order = orderRepository.save(Order.create(memberId, "orders/${++seq}.jpg", null, null, null))
            foodIds.forEach { orderItemRepository.save(OrderItem.place(order.id, it, "menu-$it", 1, null)) }
            stamp("orders", order.id, now.minusMinutes(minutesAgo))
            return order
        }

        fun reminder(memberId: Long, minutesAgo: Long, deleted: Boolean = false): Notification {
            val notification = notificationRepository.save(Notification.forMember(memberId, NotificationType.REVIEW_REMINDER, "t", "b", null))
            stamp("notification", notification.id, now.minusMinutes(minutesAgo))
            if (deleted) {
                notification.delete()
                notificationRepository.save(notification)
            }
            return notification
        }

        fun review(memberId: Long, foodId: Long) = reviewRepository.save(Review(memberId, foodId, 5))

        fun targets(afterId: Long = 0L, limit: Int = 100): List<Long> =
            orderRepository.findReviewReminderTargets(from, to, afterId, Limit.of(limit)).map { it.id }

        given("리뷰 리마인더 대상 주문 조회") {
            `when`("생성 시각이 창 안팎으로 섞여 있으면") {
                clear()
                val inWindow = order(1L, 70)
                order(2L, 30)
                order(3L, 26 * 60)
                val atEdge = order(4L, 24 * 60)

                then("1시간 이상 25시간 이하 경과한 주문만 id 오름차순으로 돌려준다") {
                    targets() shouldContainExactly listOf(inWindow.id, atEdge.id)
                }
            }

            `when`("주문 이후에 만들어진 리마인더 알림이 있는 회원의 주문이면") {
                clear()
                order(1L, 70)
                reminder(1L, 60)
                val before = order(2L, 70)
                reminder(2L, 120)

                then("제외하고, 주문 이전 알림만 있는 주문은 남긴다") {
                    targets() shouldContainExactly listOf(before.id)
                }
            }

            `when`("리마인더 알림이 있었지만 소프트 삭제됐으면") {
                clear()
                val retried = order(1L, 70)
                reminder(1L, 60, deleted = true)

                then("없는 것으로 보아 다시 대상이 된다") {
                    targets() shouldContainExactly listOf(retried.id)
                }
            }

            `when`("항목 음식 리뷰 여부가 섞여 있으면") {
                clear()
                order(1L, 70, listOf(1L, 2L))
                review(1L, 1L)
                review(1L, 2L)
                val partly = order(2L, 70, listOf(1L, 2L))
                review(2L, 1L)
                order(3L, 70, emptyList())
                val others = order(4L, 70, listOf(1L))
                review(5L, 1L)

                then("전부 리뷰한 주문과 항목 없는 주문은 빼고, 남은 항목이 있는 주문만 돌려준다") {
                    targets() shouldContainExactly listOf(partly.id, others.id)
                }
            }

            `when`("대상이 250건이면") {
                clear()
                val ids = (1..250).map { order(1000L + it, 70).id }

                then("커서 뒤의 주문을 한도만큼 읽어 이어갈 수 있다") {
                    val first = targets(limit = 100)
                    first shouldContainExactly ids.take(100)
                    val second = targets(afterId = first.last(), limit = 100)
                    second shouldContainExactly ids.drop(100).take(100)
                    val third = targets(afterId = second.last(), limit = 100)
                    third shouldContainExactly ids.drop(200)
                    targets(afterId = third.last(), limit = 100).size shouldBe 0
                }
            }
        }
    }
}
