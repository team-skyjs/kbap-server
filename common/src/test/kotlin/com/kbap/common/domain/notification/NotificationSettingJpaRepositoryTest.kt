package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.NotificationSetting
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
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

        given("설정 기록이 없는 회원") {
            `when`("회원 기준으로 조회하면") {
                clear()

                then("null 이고 기본 설정은 활동/소식 on·식사 시간 알림 on 이다") {
                    repository.findByMemberId(1L).shouldBeNull()
                    val default = NotificationSetting.defaultFor(1L)
                    default.activity shouldBe true
                    default.mealTime shouldBe true
                }
            }

            `when`("같은 회원으로 두 번 저장하면") {
                clear()
                repository.save(NotificationSetting.defaultFor(1L))

                then("유니크 제약 위반 예외를 던진다") {
                    shouldThrow<DataIntegrityViolationException> {
                        repository.saveAndFlush(NotificationSetting.defaultFor(1L))
                    }
                }
            }
        }

        given("선호 설정 변경") {
            `when`("활동/소식과 식사 시간 알림을 끄면") {
                clear()
                val setting = repository.save(NotificationSetting.defaultFor(5L))
                setting.updateActivity(false)
                setting.updateMealTime(false)
                repository.saveAndFlush(setting)

                then("두 선호가 off 로 저장된다") {
                    val found = repository.findByMemberId(5L)
                    found.shouldNotBeNull()
                    found.activity shouldBe false
                    found.mealTime shouldBe false
                }
            }
        }
    }
}
