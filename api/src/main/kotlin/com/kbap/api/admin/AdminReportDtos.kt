package com.kbap.api.admin

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportHandleResult
import com.kbap.common.domain.report.model.ReportHandleStatus
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class AdminReportHandleRequest(
    @field:NotNull(message = "result 는 필수입니다")
    @field:Schema(description = "처리 결과", example = "CONTENT_DELETED")
    val result: ReportHandleResult?,
    @field:Size(max = Report.MAX_DETAIL_LENGTH)
    @field:Schema(description = "처리 메모 — MEMBER_SUSPENDED 면 정지 사유로 필수")
    val note: String? = null,
)

data class AdminReportTargetResponse(
    val type: ReportTargetType,
    val id: Long,
    val authorMemberId: Long?,
    val contentPreview: String?,
    val exists: Boolean,
    val reportCount: Long,
)

data class AdminReportResponse(
    val id: Long,
    val reporterMemberId: Long,
    val reporterNickname: String?,
    val target: AdminReportTargetResponse,
    val reason: ReportReason,
    val detail: String?,
    val handleStatus: ReportHandleStatus,
    val handleResult: ReportHandleResult?,
    val handledBy: Long?,
    val handledAt: LocalDateTime?,
    val handleNote: String?,
    val createdAt: LocalDateTime,
)

data class AdminReportPageResponse(
    val items: List<AdminReportResponse>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class AdminReportHandleResponse(
    val report: AdminReportResponse,
    val handledReportIds: List<Long>,
)
