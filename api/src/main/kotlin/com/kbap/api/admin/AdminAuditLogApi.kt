package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import java.time.LocalDateTime

@Tag(name = "관리자 감사 이력", description = "관리자 조작(누가·언제·무엇을·이전값→이후값) 조회 — 삭제·수정 API 는 없다")
@SecurityRequirement(name = "bearerAuth")
interface AdminAuditLogApi {
    @Operation(
        summary = "감사 이력 조회",
        description = "대상 종류/식별자·조작자·조작 종류·기간으로 거른 감사 이력을 최신순 페이지로 돌려준다. before/after 는 변경된 필드만 담는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회 성공"),
            ApiResponse(responseCode = "401", description = "액세스 토큰 부재·위조·만료"),
            ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ],
    )
    fun getAuditLogs(
        @Parameter(description = "대상 종류") targetType: AdminAuditTargetType?,
        @Parameter(description = "대상 식별자") targetId: Long?,
        @Parameter(description = "조작자 관리자 계정 id") adminAccountId: Long?,
        @Parameter(description = "조작 종류") action: AdminAuditAction?,
        @Parameter(description = "기간 시작(포함, ISO-8601)") from: LocalDateTime?,
        @Parameter(description = "기간 끝(제외, ISO-8601)") to: LocalDateTime?,
        @Parameter(description = "1-base 페이지", example = "1") page: Int,
        @Parameter(description = "페이지 크기(최대 200)", example = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminAuditLogPageResponse>>
}
