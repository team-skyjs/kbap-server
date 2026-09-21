package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.Notification
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.domain.notification.model.NotificationType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationSettingJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var notificationRepository: NotificationJpaRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    init {
        fun clear() {
            notificationRepository.deleteAll()
            repository.deleteAll()
        }

        given("기기별 설정 저장") {
            `when`("같은 회원의 기기 두 대를 저장하면") {
                clear()
                repository.save(NotificationSetting.defaultFor(1L, "dev-a").apply { updateActivity(true) })
                repository.save(NotificationSetting.defaultFor(1L, "dev-b"))

                then("둘 다 남고 기기별로 자기 행을 돌려준다") {
                    val a = repository.findByMemberIdAndInstallationId(1L, "dev-a")
                    val b = repository.findByMemberIdAndInstallationId(1L, "dev-b")
                    a.shouldNotBeNull().activity shouldBe true
                    b.shouldNotBeNull().activity shouldBe false
                }
            }

            `when`("같은 (회원, 기기) 쌍을 두 번 저장하면") {
                clear()
                repository.save(NotificationSetting.defaultFor(1L, "dev-a"))

                then("유니크 제약 위반 예외를 던진다") {
                    shouldThrow<DataIntegrityViolationException> {
                        repository.saveAndFlush(NotificationSetting.defaultFor(1L, "dev-a"))
                    }
                }
            }

            `when`("설정 기록이 없는 기기를 조회하면") {
                clear()

                then("null 이고 기본 설정은 세 토글 전부 off 다") {
                    repository.findByMemberIdAndInstallationId(1L, "dev-a").shouldBeNull()
                    val default = NotificationSetting.defaultFor(1L, "dev-a")
                    default.activity shouldBe false
                    default.mealTime shouldBe false
                    default.news shouldBe false
                }
            }
        }

        given("알림 발송 대상 회원 커서 조회") {
            val since = LocalDateTime.of(2026, 9, 15, 11, 0)
            val type = NotificationType.SCAN_SUGGESTION
            fun notified(memberId: Long, notificationType: NotificationType, createdAt: LocalDateTime, deleted: Boolean = false) {
                val saved = notificationRepository.save(
                    Notification(memberId = memberId, type = notificationType, title = "t", body = "b").apply { if (deleted) delete() },
                )
                jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE id = ?", createdAt, saved.id)
            }

            `when`("회원별로 소식 토글이 섞여 있으면") {
                clear()
                repository.save(NotificationSetting.defaultFor(21L, "dev-a").apply { updateNews(true) })
                repository.save(NotificationSetting.defaultFor(21L, "dev-b").apply { updateNews(true) })
                repository.save(NotificationSetting.defaultFor(22L, "dev-a"))
                repository.save(NotificationSetting.defaultFor(23L, "dev-a").apply { updateNews(true) })
                repository.save(NotificationSetting.defaultFor(24L, "dev-a").apply { updateNews(true); delete() })
                repository.save(NotificationSetting.defaultFor(25L, "dev-a").apply { updateNews(true) })

                then("켜진 행이 있는 회원 id 를 중복 없이 오름차순으로 돌려주고 소프트 삭제 행은 제외한다") {
                    repository.findNewsMemberIdsNotNotifiedSince(type, since, 0L, Limit.of(10)) shouldContainExactly listOf(21L, 23L, 25L)
                }
                then("커서보다 큰 회원만 limit 건까지 돌려준다") {
                    repository.findNewsMemberIdsNotNotifiedSince(type, since, 21L, Limit.of(1)) shouldContainExactly listOf(23L)
                    repository.findNewsMemberIdsNotNotifiedSince(type, since, 25L, Limit.of(10)) shouldContainExactly emptyList()
                }
            }

            `when`("기준 시각 이후 같은 유형 알림을 이미 받은 회원이 섞여 있으면") {
                clear()
                (31L..35L).forEach { repository.save(NotificationSetting.defaultFor(it, "dev-a").apply { updateNews(true) }) }
                notified(31L, type, since)
                notified(32L, type, since.minusSeconds(1))
                notified(33L, NotificationType.HELPFUL, since.plusHours(1))
                notified(34L, type, since.plusHours(1), deleted = true)

                then("기준 시각 이후의 활성 같은 유형 알림이 있는 회원만 빠진다") {
                    repository.findNewsMemberIdsNotNotifiedSince(type, since, 0L, Limit.of(10)) shouldContainExactly listOf(32L, 33L, 34L, 35L)
                }
            }
        }

        given("회원 기준 조회") {
            `when`("회원의 기기 행이 여럿이면") {
                clear()
                repository.save(NotificationSetting.defaultFor(7L, "dev-a"))
                repository.save(NotificationSetting.defaultFor(7L, "dev-b"))
                repository.save(NotificationSetting.defaultFor(8L, "dev-c"))

                then("그 회원의 기기 행만 돌려준다") {
                    repository.findByMemberId(7L)
                        .map { it.installationId }
                        .sortedBy { it } shouldContainExactly listOf("dev-a", "dev-b")
                }
            }
        }

        given("선호 설정 변경") {
            `when`("세 토글을 모두 켜면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(5L, "dev-a"))
                setting.updateActivity(true)
                setting.updateMealTime(true)
                setting.updateNews(true)
                repository.saveAndFlush(setting)

                then("세 토글이 on 으로 저장된다") {
                    val found = repository.findByMemberIdAndInstallationId(5L, "dev-a")
                    found.shouldNotBeNull()
                    found.activity shouldBe true
                    found.mealTime shouldBe true
                    found.news shouldBe true
                }
            }
        }
    }
}
