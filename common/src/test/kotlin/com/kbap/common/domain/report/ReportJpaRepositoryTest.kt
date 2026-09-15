package com.kbap.common.domain.report

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class ReportJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var reportJpaRepository: ReportJpaRepository

    init {
        fun report(
            reporterMemberId: Long,
            targetId: Long,
            reason: ReportReason = ReportReason.SPAM,
            detail: String? = null,
        ) = Report(
            reporterMemberId = reporterMemberId,
            targetType = ReportTargetType.REVIEW,
            targetId = targetId,
            reason = reason,
            detail = detail,
        )

        given("신고 저장") {
            `when`("사유와 상세 설명을 담아 저장하면") {
                then("저장된 신고가 사유·상세·대상을 그대로 갖는다") {
                    val saved = reportJpaRepository.save(report(reporterMemberId = 1L, targetId = 10L, reason = ReportReason.OTHER, detail = "기타 사유"))

                    (saved.id > 0) shouldBe true
                    saved.reporterMemberId shouldBe 1L
                    saved.targetType shouldBe ReportTargetType.REVIEW
                    saved.targetId shouldBe 10L
                    saved.reason shouldBe ReportReason.OTHER
                    saved.detail shouldBe "기타 사유"
                }
            }

            `when`("같은 (신고자, 대상 타입, 대상)으로 다시 저장하면") {
                then("재신고가 허용돼 행이 하나 더 쌓이고, 제외 대상 조회는 id 를 한 번만 준다") {
                    reportJpaRepository.save(report(reporterMemberId = 2L, targetId = 20L))
                    reportJpaRepository.save(report(reporterMemberId = 2L, targetId = 20L, reason = ReportReason.ABUSE))

                    reportJpaRepository.findAll().count { it.reporterMemberId == 2L && it.targetId == 20L } shouldBe 2
                    reportJpaRepository.findTargetIdsByReporterMemberIdAndTargetType(2L, ReportTargetType.REVIEW)
                        .count { it == 20L } shouldBe 1
                }
            }
        }

        given("신고자 식별자 불변식") {
            `when`("회원 ID 와 설치 ID 가 모두 없는 신고를 저장하면") {
                then("CHECK 제약이 거절한다 — 엔티티 메타데이터에도 같은 제약이 선언돼 있다") {
                    shouldThrow<DataIntegrityViolationException> {
                        reportJpaRepository.saveAndFlush(
                            Report(
                                reporterMemberId = null,
                                reporterInstallationId = null,
                                targetType = ReportTargetType.REVIEW,
                                targetId = 90L,
                                reason = ReportReason.SPAM,
                            ),
                        )
                    }
                }
            }
        }

        given("신고한 대상 id 목록 조회") {
            `when`("한 회원이 같은 타입의 대상 여럿을 신고했으면") {
                then("그 회원이 신고한 대상 id 만 전부 반환한다") {
                    reportJpaRepository.save(report(reporterMemberId = 4L, targetId = 40L))
                    reportJpaRepository.save(report(reporterMemberId = 4L, targetId = 41L))
                    reportJpaRepository.save(report(reporterMemberId = 5L, targetId = 42L))

                    reportJpaRepository.findTargetIdsByReporterMemberIdAndTargetType(4L, ReportTargetType.REVIEW)
                        .shouldContainExactlyInAnyOrder(40L, 41L)
                }
            }

            `when`("신고 이력이 없는 회원이면") {
                then("빈 목록을 반환한다") {
                    reportJpaRepository.findTargetIdsByReporterMemberIdAndTargetType(999L, ReportTargetType.REVIEW) shouldBe emptyList()
                }
            }
        }
    }
}
