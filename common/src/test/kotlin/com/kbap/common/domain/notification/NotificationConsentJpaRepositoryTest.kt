package com.kbap.common.domain.notification

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationConsentType.MARKETING_PRIVACY
import com.kbap.common.domain.notification.model.NotificationConsentType.MARKETING_RECEIVE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime

@SpringBootTest
@Import(MySqlContainerConfig::class)
class NotificationConsentJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: NotificationConsentJpaRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    init {
        fun clear() = repository.deleteAll()
        val now = LocalDateTime.of(2026, 9, 7, 12, 0)

        given("회원 동의") {
            `when`("문구 버전 2 로 동의하면") {
                clear()
                repository.save(NotificationConsent.grantForMember(memberId = 1L, installationId = "inst-1", type = MARKETING_RECEIVE, consentVersion = 2, now = now))

                then("열린 동의 1건이 서버 시각·버전·동의 기기와 함께 남는다") {
                    val open = repository.findOpenByMemberId(1L)
                    open shouldHaveSize 1
                    open.first().grantedAt shouldBe now
                    open.first().consentVersion shouldBe 2
                    open.first().installationId shouldBe "inst-1"
                    open.first().revokedAt.shouldBeNull()
                    open.first().isOpen() shouldBe true
                }
            }

            `when`("개인정보 수집·이용 동의와 광고성 수신 동의를 각각 하면") {
                clear()
                repository.save(NotificationConsent.grantForMember(1L, "inst-1", MARKETING_PRIVACY, 1, now))
                repository.save(NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 1, now))

                then("종류가 다른 두 열린 행이 한 회원에 공존하고 종류로 구분된다") {
                    val open = repository.findOpenByMemberId(1L)
                    open shouldHaveSize 2
                    open.groupBy { it.consentType }.keys shouldBe setOf(MARKETING_PRIVACY, MARKETING_RECEIVE)
                    repository.findById(open.first().id).get().consentType shouldBe open.first().consentType
                }
            }

            `when`("동의를 철회하면") {
                clear()
                val saved = repository.save(NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 2, now))
                saved.revoke(now.plusDays(3))
                repository.saveAndFlush(saved)

                then("열린 동의는 없어지지만 행은 철회 시각과 함께 보존된다") {
                    repository.findOpenByMemberId(1L).shouldBeEmpty()
                    val kept = repository.findById(saved.id).get()
                    kept.grantedAt shouldBe now
                    kept.revokedAt shouldBe now.plusDays(3)
                    kept.isOpen() shouldBe false
                }
            }

            `when`("두 종류의 열린 동의가 있는 상태에서 회원 기준으로 전부 닫으면") {
                clear()
                repository.save(NotificationConsent.grantForMember(1L, "inst-1", MARKETING_PRIVACY, 2, now))
                repository.save(NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 2, now))
                val closed = transactionTemplate.execute { repository.closeOpenByMemberId(1L, now.plusDays(1)) }

                then("두 종류 모두 닫힌다") {
                    closed shouldBe 2
                    repository.findOpenByMemberId(1L).shouldBeEmpty()
                }
            }

            `when`("이미 철회된 동의를 다시 철회하면") {
                val consent = NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 2, now)
                consent.revoke(now.plusDays(1))

                then("허용되지 않는 전이라 예외를 던진다") {
                    shouldThrow<IllegalStateException> { consent.revoke(now.plusDays(2)) }
                }
            }
        }

        given("발송 가능 판정") {
            `when`("요구 버전이 2 일 때") {
                val v2 = NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 2, now)
                val v10 = NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 10, now)
                val v1 = NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 1, now)
                val revoked = NotificationConsent.grantForMember(1L, "inst-1", MARKETING_RECEIVE, 2, now).apply { revoke(now.plusDays(1)) }

                then("열린 동의이고 버전이 요구 이상일 때만 참이다") {
                    v2.allows(2) shouldBe true
                    v10.allows(2) shouldBe true
                    v1.allows(2) shouldBe false
                    revoked.allows(2) shouldBe false
                }
            }
        }

    }
}
