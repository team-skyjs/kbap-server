package com.kbap.api.report

import com.kbap.common.domain.report.model.Report
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

@Schema(description = "신고 접수 요청")
data class ReportCreateRequest(
    @field:NotNull(message = "targetType 은 필수입니다")
    @field:Schema(description = "신고 대상 타입", example = "REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
    val targetType: ReportTargetType?,

    @field:NotNull(message = "targetId 는 필수입니다")
    @field:Schema(description = "신고 대상 콘텐츠 id", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    val targetId: Long?,

    @field:NotNull(message = "reason 은 필수입니다")
    @field:Schema(description = "신고 사유", example = "SPAM", requiredMode = Schema.RequiredMode.REQUIRED)
    val reason: ReportReason?,

    @field:Size(max = Report.MAX_DETAIL_LENGTH, message = "상세 설명은 최대 500자입니다")
    @field:Schema(description = "상세 설명(옵션, 최대 500자)", example = "광고 링크가 반복 게시됨")
    val detail: String? = null,
)
