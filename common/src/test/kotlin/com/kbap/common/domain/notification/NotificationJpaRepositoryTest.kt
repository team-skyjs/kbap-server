package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationJpaRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        fun clear() = repository.deleteAll()
        val now = LocalDateTime.of(2026, 9, 7, 12, 0)

        fun helpful(memberId: Long, index: Int) = Notification.forMember(
            memberId = memberId,
            type = NotificationType.HELPFUL,
            title = "title-$index",
            body = "body-$index",
            data = mapOf("type" to "HELPFUL", "reviewId" to index),
        )

        given("회원 알림 목록") {
            `when`("알림 3건을 저장하고 회원 기준으로 조회하면") {
                clear()
                repeat(3) { repository.save(helpful(1L, it)) }

                then("최신순 3건이고 각 건은 유형·제목·본문·이동 정보·미읽음 상태를 가진다") {
                    val page = repository.findPageByMemberId(1L, null, PageRequest.of(0, 20))
                    page shouldHaveSize 3
                    page.map { it.title } shouldContainExactly listOf("title-2", "title-1", "title-0")
                    page.first().type shouldBe NotificationType.HELPFUL
                    page.first().body shouldBe "body-2"
                    page.first().data shouldBe mapOf("type" to "HELPFUL", "reviewId" to 2)
                    page.first().readAt.shouldBeNull()
                    page.first().isRead() shouldBe false
                }
            }

            `when`("25건을 20건씩 커서로 넘기면") {
                clear()
                repeat(25) { repository.save(helpful(1L, it)) }

                then("두 페이지에 중복·누락 없이 25건이 최신순으로 나온다") {
                    val first = repository.findPageByMemberId(1L, null, PageRequest.of(0, 20))
                    val second = repository.findPageByMemberId(1L, first.last().id, PageRequest.of(0, 20))
                    first shouldHaveSize 20
                    second shouldHaveSize 5
                    (first + second).map { it.id } shouldContainExactly (first + second).map { it.id }.sortedDescending()
                    (first + second).map { it.id }.toSet() shouldHaveSize 25
                }
            }
        }

        given("읽음 처리") {
            `when`("미읽음 3건 중 1건을 읽으면") {
                clear()
                val saved = (0 until 3).map { repository.save(helpful(2L, it)) }
                saved[0].markRead(now)
                saved[0].markRead(now.plusHours(1))
                repository.saveAndFlush(saved[0])

                then("미읽음 수는 2가 되고 읽음 시각은 처음 값이다") {
                    repository.countByMemberIdAndReadAtIsNull(2L) shouldBe 2
                    repository.findById(saved[0].id).get().readAt shouldBe now
                }
            }

            `when`("전체 읽음 처리하면") {
                clear()
                val saved = (0 until 3).map { repository.save(helpful(3L, it)) }
                saved[0].markRead(now)
                repository.saveAndFlush(saved[0])
                val updated = transactionTemplate.execute { repository.markAllReadByMemberId(3L, now.plusDays(1)) }

                then("미읽음이던 2건만 갱신되고 미읽음 수는 0 이다") {
                    updated shouldBe 2
                    repository.countByMemberIdAndReadAtIsNull(3L) shouldBe 0
                }
            }
        }

        given("이동 정보 페이로드") {
            `when`("문자열과 숫자가 섞인 data 를 저장하면") {
                clear()
                val saved = repository.save(
                    Notification.forMember(
                        memberId = 4L,
                        type = NotificationType.REVIEW_REMINDER,
                        title = "t",
                        body = "b",
                        data = mapOf("type" to "REVIEW_REMINDER", "foodId" to "12", "notificationId" to 34),
                    ),
                )

                then("값 타입이 유지된 채 되읽힌다") {
                    repository.findById(saved.id).get().data shouldBe
                        mapOf("type" to "REVIEW_REMINDER", "foodId" to "12", "notificationId" to 34)
                }
            }
        }

        given("음식 발견 90일 이력") {
            `when`("89일 전·91일 전·삭제된·다른 유형·다른 회원 알림이 섞여 있으면") {
                clear()
                fun discovery(memberId: Long, foodId: Long, daysAgo: Long, type: NotificationType = NotificationType.FOOD_DISCOVERY, deleted: Boolean = false) {
                    val saved = repository.save(Notification(memberId = memberId, type = type, title = "t", body = "b", foodId = foodId))
                    if (deleted) {
                        saved.delete()
                        repository.save(saved)
                    }
                    jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE id = ?", now.minusDays(daysAgo), saved.id)
                }
                discovery(10L, 1L, 89)
                discovery(10L, 2L, 91)
                discovery(10L, 3L, 1, deleted = true)
                discovery(10L, 4L, 1, type = NotificationType.SCAN_SUGGESTION)
                discovery(11L, 5L, 10)
                discovery(12L, 6L, 10)
                repository.save(Notification(memberId = 10L, type = NotificationType.FOOD_DISCOVERY, title = "t", body = "b"))

                then("기간 안·같은 유형·살아 있는·food_id 있는 행의 (회원, 음식)만 나온다") {
                    repository.findFoodIdsSince(listOf(10L, 11L), NotificationType.FOOD_DISCOVERY, now.minusDays(90))
                        .map { it.memberId to it.foodId }.toSet() shouldBe setOf(10L to 1L, 11L to 5L)
                }
            }
        }
    }
}
