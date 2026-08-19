package com.kbap.common.domain.member

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.review.ReviewTestApp
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest(classes = [ReviewTestApp::class])
@Import(MySqlContainerConfig::class)
class MemberScanReservationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var memberRepository: MemberJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        fun newMember(uid: String, scanCount: Int = 0, unlocked: Boolean = false): Member =
            memberRepository.save(
                Member.signUp(SocialIdentity(SocialProvider.GOOGLE, uid, null)).apply {
                    this.scanCount = scanCount
                    this.scanUnlocked = unlocked
                },
            )

        fun reserveInTx(memberId: Long): Int =
            TransactionTemplate(transactionManager).execute {
                memberRepository.reserveScan(memberId, Member.FREE_SCAN_LIMIT)
            }!!

        fun releaseInTx(memberId: Long): Int =
            TransactionTemplate(transactionManager).execute {
                memberRepository.releaseScan(memberId)
            }!!

        fun scanCountOf(memberId: Long): Int = memberRepository.findById(memberId).get().scanCount

        given("reserveScan — 원자 선점") {
            `when`("잔여 1회인 회원에게 동시 요청 5개가 몰리면") {
                then("정확히 1개만 선점에 성공하고 카운트는 3에서 멈춘다") {
                    val member = newMember("reserve-race", scanCount = 2)
                    val executor = Executors.newFixedThreadPool(5)
                    val startGate = CountDownLatch(1)

                    val results = (1..5).map {
                        executor.submit<Int> {
                            startGate.await()
                            reserveInTx(member.id)
                        }
                    }
                    startGate.countDown()
                    val granted = results.sumOf { it.get() }
                    executor.shutdown()

                    granted shouldBe 1
                    scanCountOf(member.id) shouldBe 3
                }
            }
            `when`("무료 한도를 소진한 미해금 회원이면") {
                then("선점이 거절된다(0행)") {
                    val member = newMember("reserve-exhausted", scanCount = 3)
                    reserveInTx(member.id) shouldBe 0
                    scanCountOf(member.id) shouldBe 3
                }
            }
            `when`("해금된 회원이면") {
                then("한도와 무관하게 선점되고 카운트가 계속 쌓인다") {
                    val member = newMember("reserve-unlocked", scanCount = 10, unlocked = true)
                    reserveInTx(member.id) shouldBe 1
                    scanCountOf(member.id) shouldBe 11
                }
            }
        }

        given("releaseScan — 선점 반환(보상)") {
            `when`("선점 후 실패해 반환하면") {
                then("카운트가 원복된다") {
                    val member = newMember("release-basic", scanCount = 2)
                    reserveInTx(member.id) shouldBe 1
                    releaseInTx(member.id) shouldBe 1
                    scanCountOf(member.id) shouldBe 2
                }
            }
            `when`("카운트가 0인 회원에게 반환이 호출되면") {
                then("음수로 내려가지 않는다(0행)") {
                    val member = newMember("release-floor", scanCount = 0)
                    releaseInTx(member.id) shouldBe 0
                    scanCountOf(member.id) shouldBe 0
                }
            }
        }
    }
}
