package com.kbap.common.domain.report.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class ReportHandleTest : BehaviorSpec({
    given("신고 처리 전이") {
        `when`("PENDING 신고를 처리하면") {
            then("HANDLED 와 함께 결과·처리자·시각이 전부 채워진다 — HANDLED 면 셋 다 있다는 불변식") {
                val report = Report.byGuest("install-handle-test", ReportTargetType.REVIEW, 1L, ReportReason.SPAM)
                val at = LocalDateTime.of(2026, 10, 1, 12, 0)

                report.handle(ReportHandleResult.DISMISSED, 7L, at, "메모")

                report.handleStatus shouldBe ReportHandleStatus.HANDLED
                report.handleResult shouldBe ReportHandleResult.DISMISSED
                report.handledBy shouldBe 7L
                report.handledAt shouldBe at
                report.handleNote shouldBe "메모"
            }
        }

        `when`("이미 처리된 신고를 다시 처리하면") {
            then("예외를 던진다 — 처리 결과를 덮어쓰지 않는다") {
                val report = Report.byGuest("install-handle-test", ReportTargetType.REVIEW, 1L, ReportReason.SPAM)
                report.handle(ReportHandleResult.DISMISSED, 7L, LocalDateTime.now(), null)

                shouldThrow<IllegalStateException> { report.handle(ReportHandleResult.CONTENT_DELETED, 8L, LocalDateTime.now(), null) }
                report.handleResult shouldBe ReportHandleResult.DISMISSED
            }
        }

        `when`("새 신고를 만들면") {
            then("PENDING 이고 처리 필드는 전부 비어 있다") {
                val report = Report.byGuest("install-handle-test", ReportTargetType.REVIEW, 1L, ReportReason.SPAM)

                report.handleStatus shouldBe ReportHandleStatus.PENDING
                report.handleResult shouldBe null
                report.handledBy shouldBe null
                report.handledAt shouldBe null
            }
        }
    }
})
