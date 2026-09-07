package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationDeviceJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationDeviceJpaRepository

    init {
        fun clear() = repository.deleteAll()
        val now = LocalDateTime.of(2026, 9, 7, 12, 0)

        fun device(installationId: String = "inst-1", memberId: Long? = null) = NotificationDevice.register(
            installationId = installationId,
            expoToken = "ExponentPushToken[$installationId]",
            platform = DevicePlatform.IOS,
            lang = "ko",
            memberId = memberId,
        )

        given("기기 등록") {
            `when`("게스트 기기를 저장하면") {
                clear()
                repository.save(device())

                then("설치 식별자로 1건이 조회되고 회원 연결은 비어 있다") {
                    val found = repository.findByInstallationId("inst-1")
                    found.shouldNotBeNull()
                    found.memberId.shouldBeNull()
                    found.expoToken shouldBe "ExponentPushToken[inst-1]"
                    found.platform shouldBe DevicePlatform.IOS
                    found.lang shouldBe "ko"
                    found.tokenInvalidAt.shouldBeNull()
                    found.isTokenValid() shouldBe true
                }
            }

            `when`("같은 설치 식별자로 다시 저장하면") {
                clear()
                repository.save(device())

                then("유니크 제약 위반 예외를 던진다") {
                    shouldThrow<DataIntegrityViolationException> {
                        repository.saveAndFlush(device())
                    }
                }
            }
        }

        given("회원 연결과 해제") {
            `when`("게스트 기기에 회원을 연결했다가 해제하면") {
                clear()
                val saved = repository.save(device())
                saved.linkMember(7L)
                repository.saveAndFlush(saved)
                val linked = repository.findByInstallationId("inst-1")!!
                val linkedMemberId = linked.memberId
                linked.unlinkMember()
                repository.saveAndFlush(linked)

                then("연결 시 회원이 붙고 해제 시 회원만 비워지며 토큰·언어는 남는다") {
                    linkedMemberId shouldBe 7L
                    val found = repository.findByInstallationId("inst-1")!!
                    found.memberId.shouldBeNull()
                    found.expoToken shouldBe "ExponentPushToken[inst-1]"
                    found.lang shouldBe "ko"
                }
            }

            `when`("한 회원이 기기 두 대에서 로그인하면") {
                clear()
                repository.save(device(installationId = "inst-a", memberId = 9L))
                repository.save(device(installationId = "inst-b", memberId = 9L))

                then("회원 기준으로 두 기기가 모두 조회된다") {
                    repository.findByMemberId(9L) shouldHaveSize 2
                }
            }
        }

        given("재등록 갱신") {
            `when`("같은 기기가 새 토큰으로 다시 등록하면") {
                clear()
                val saved = repository.save(device())
                val originalId = saved.id
                saved.renew(expoToken = "ExponentPushToken[new]", platform = DevicePlatform.ANDROID, lang = "en")
                repository.saveAndFlush(saved)

                then("기존 기록의 토큰·플랫폼·언어만 바뀌고 id 는 그대로다") {
                    val found = repository.findByInstallationId("inst-1")!!
                    found.id shouldBe originalId
                    found.expoToken shouldBe "ExponentPushToken[new]"
                    found.platform shouldBe DevicePlatform.ANDROID
                    found.lang shouldBe "en"
                }
            }
        }

        given("무효 토큰") {
            `when`("Expo 가 기기 미등록을 알려 토큰을 무효 처리하면") {
                clear()
                val saved = repository.save(device())
                saved.markTokenInvalid(now)
                repository.saveAndFlush(saved)

                then("기기 행은 남고 무효 시각만 찍혀 설치 식별자 조회가 계속 된다") {
                    val found = repository.findByInstallationId("inst-1")
                    found.shouldNotBeNull()
                    found.tokenInvalidAt shouldBe now
                    found.isTokenValid() shouldBe false
                }
            }

            `when`("무효 처리된 기기가 다시 등록하면") {
                clear()
                val saved = repository.save(device())
                saved.markTokenInvalid(now)
                repository.saveAndFlush(saved)
                val found = repository.findByInstallationId("inst-1")!!
                found.renew(expoToken = "ExponentPushToken[again]", platform = DevicePlatform.IOS, lang = "ko")
                repository.saveAndFlush(found)

                then("같은 행이 유효 토큰으로 되살아난다") {
                    val revived = repository.findByInstallationId("inst-1")!!
                    revived.id shouldBe saved.id
                    revived.tokenInvalidAt.shouldBeNull()
                    revived.isTokenValid() shouldBe true
                    revived.expoToken shouldBe "ExponentPushToken[again]"
                }
            }
        }
    }
}
