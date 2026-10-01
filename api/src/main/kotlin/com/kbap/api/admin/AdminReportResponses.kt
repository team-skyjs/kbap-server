package com.kbap.api.admin

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleResult
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

@Schema(description = "어드민 신고 목록 — 대상 단위로 묶은 행")
data class AdminReportPageResponse(
    val items: List<Group>,
    val page: Int,
    val size: Int,
    @field:Schema(description = "필터 조건에 맞는 대상 수", example = "12")
    val totalCount: Long,
    val totalPages: Int,
) {
    @Schema(description = "신고 대상 한 건과 그 대상에 쌓인 신고들")
    data class Group(
        val target: Target,
        @field:Schema(description = "아직 처리 안 된 신고 수", example = "4")
        val pendingCount: Int,
        @field:Schema(description = "그 대상의 전체 신고 수(재신고 포함)", example = "4")
        val totalCount: Int,
        @field:Schema(
            description = "신고한 사람 수 — 회원 id 가 있으면 회원 단위, 없으면(게스트) 설치 id 단위로 중복 제거. " +
                "같은 기기에서 게스트로 한 번, 로그인해 한 번 신고하면 2명으로 센다",
            example = "3",
        )
        val reporterCount: Int,
        @field:Schema(description = "가장 최근 신고의 사유", example = "SPAM")
        val latestReason: String,
        val latestReportedAt: LocalDateTime,
        @field:Schema(description = "펼침용 신고 목록 — 최근순")
        val items: List<Item>,
    )

    @Schema(description = "신고 대상")
    data class Target(
        @field:Schema(example = "REVIEW")
        val type: String,
        val id: Long,
        @field:Schema(description = "대상 작성자 회원 id", nullable = true)
        val authorMemberId: Long?,
        @field:Schema(description = "본문 앞 100자", nullable = true)
        val contentPreview: String?,
        @field:Schema(description = "대상이 아직 노출 중인지 — 삭제됐으면 false")
        val exists: Boolean,
    )

    @Schema(description = "신고 한 건")
    data class Item(
        val id: Long,
        @field:Schema(description = "신고자 표시 — 회원이면 member:{id}, 게스트면 게스트(설치 id 앞 8자)", example = "게스트(6d3f2a1b)")
        val reporterLabel: String,
        @field:Schema(nullable = true)
        val reporterMemberId: Long?,
        @field:Schema(nullable = true)
        val reporterInstallationId: String?,
        val reason: String,
        @field:Schema(nullable = true)
        val detail: String?,
        @field:Schema(example = "PENDING")
        val handleStatus: String,
        @field:Schema(nullable = true, example = "DISMISSED")
        val handleResult: String?,
        @field:Schema(description = "처리한 관리자 계정 id", nullable = true)
        val handledBy: Long?,
        @field:Schema(nullable = true)
        val handledAt: LocalDateTime?,
        @field:Schema(nullable = true)
        val handleNote: String?,
        val createdAt: LocalDateTime,
    ) {
        companion object {
            fun from(report: Report) = Item(
                id = report.id,
                reporterLabel = report.reporterLabel,
                reporterMemberId = report.reporterMemberId,
                reporterInstallationId = report.reporterInstallationId,
                reason = report.reason.name,
                detail = report.detail,
                handleStatus = report.handleStatus.name,
                handleResult = report.handleResult?.name,
                handledBy = report.handledBy,
                handledAt = report.handledAt,
                handleNote = report.handleNote,
                createdAt = report.createdAt,
            )
        }
    }

    companion object {
        fun from(result: AdminReportPageResult) = AdminReportPageResponse(
            items = result.groups.map { group ->
                Group(
                    target = Target(
                        type = group.target.type.name,
                        id = group.target.id,
                        authorMemberId = group.target.authorMemberId,
                        contentPreview = group.target.contentPreview,
                        exists = group.target.exists,
                    ),
                    pendingCount = group.pendingCount,
                    totalCount = group.totalCount,
                    reporterCount = group.reporterCount,
                    latestReason = group.latest.reason.name,
                    latestReportedAt = group.latest.createdAt,
                    items = group.items.map(Item::from),
                )
            },
            page = result.page,
            size = result.size,
            totalCount = result.totalCount,
            totalPages = if (result.size == 0) 0 else ((result.totalCount + result.size - 1) / result.size).toInt(),
        )
    }
}

@Schema(description = "신고 처리 요청")
data class AdminReportHandleRequest(
    @field:NotNull(message = "result 는 필수입니다")
    @field:Schema(description = "DISMISSED(무시 — 콘텐츠 유지) · CONTENT_DELETED(리뷰 소프트 삭제)", example = "DISMISSED")
    val result: ReportHandleResult?,

    @field:Size(max = Report.MAX_HANDLE_NOTE_LENGTH, message = "note 는 최대 500자입니다")
    @field:Schema(description = "처리 메모", nullable = true)
    val note: String? = null,
)

@Schema(description = "신고 처리 결과")
data class AdminReportHandleResponse(
    @field:Schema(example = "REVIEW")
    val targetType: String,
    val targetId: Long,
    @field:Schema(example = "CONTENT_DELETED")
    val result: String,
    @field:Schema(description = "이번에 HANDLED 로 바뀐 신고 수(같은 대상 PENDING 전부)", example = "4")
    val handledCount: Int,
    @field:Schema(description = "이번 처리로 대상이 삭제됐는지 — 이미 삭제된 대상이면 false")
    val contentDeleted: Boolean,
) {
    companion object {
        fun from(result: AdminReportHandleResult) = AdminReportHandleResponse(
            targetType = result.targetType.name,
            targetId = result.targetId,
            result = result.result.name,
            handledCount = result.handledCount,
            contentDeleted = result.contentDeleted,
        )
    }
}
