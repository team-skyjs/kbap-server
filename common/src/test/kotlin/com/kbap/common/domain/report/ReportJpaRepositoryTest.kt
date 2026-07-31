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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

@SpringBootTest(classes = [ReportTestApp::class])
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
                then("유니크 제약 위반 예외를 던진다") {
                    reportJpaRepository.save(report(reporterMemberId = 2L, targetId = 20L))

                    shouldThrow<DataIntegrityViolationException> {
                        reportJpaRepository.save(report(reporterMemberId = 2L, targetId = 20L, reason = ReportReason.ABUSE))
                    }
                }
            }
        }

        given("중복 신고 여부 조회") {
            `when`("이미 신고한 대상이면") {
                then("true 를 반환한다") {
                    reportJpaRepository.save(report(reporterMemberId = 3L, targetId = 30L))

                    reportJpaRepository.existsByReporterMemberIdAndTargetTypeAndTargetId(3L, ReportTargetType.REVIEW, 30L) shouldBe true
                }
            }

            `when`("신고한 적 없는 대상이면") {
                then("false 를 반환한다") {
                    reportJpaRepository.existsByReporterMemberIdAndTargetTypeAndTargetId(3L, ReportTargetType.REVIEW, 31L) shouldBe false
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
