package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.NotificationSetting
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

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationSettingJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationSettingJpaRepository

    init {
        fun clear() = repository.deleteAll()

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
