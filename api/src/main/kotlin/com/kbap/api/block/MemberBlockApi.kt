package com.kbap.api.block

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "회원 차단", description = "리뷰 등 UGC 노출에서 특정 회원의 글을 가리는 단방향 차단")
@SecurityRequirement(name = "bearerAuth")
interface MemberBlockApi {
    @Operation(
        summary = "회원 차단",
        description = """
            대상 회원을 차단한다. 차단 직후부터 내가 보는 리뷰 목록에서 그 회원의 글이 사라진다(집계는 불변).
            이미 차단 중이면 멱등하게 200, 해제했던 회원이면 다시 차단된다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "차단 완료(신규·재차단·이미 차단 중 동일)"),
            ApiResponse(responseCode = "400", description = "자기 자신 차단(BLOCK-001) 또는 memberId 누락"),
            ApiResponse(responseCode = "404", description = "존재하지 않거나 탈퇴한 회원(BLOCK-002)"),
            ApiResponse(responseCode = "401", description = "미인증(토큰 부재·위조·만료)"),
        ],
    )
    fun block(
        memberId: Long,
        request: MemberBlockRequest,
    ): ResponseEntity<BaseResponse<Unit>>
}
