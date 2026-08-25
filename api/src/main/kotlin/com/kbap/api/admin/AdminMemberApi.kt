package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.member.model.MemberStatus
import com.kbap.common.domain.member.model.SocialProvider
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import java.time.LocalDate

@Tag(name = "관리자 회원", description = "회원 탐색(검색·탈퇴 포함·활동 이력)과 조치(제재·프로필 초기화·스캔 해제·강제 탈퇴)")
@SecurityRequirement(name = "bearerAuth")
interface AdminMemberApi {
    @Operation(
        summary = "회원 목록",
        description = "`q`(숫자면 id, 아니면 닉네임 포함)·`email`(원문 포함 검색, 응답은 마스킹)·가입 경로·상태·온보딩·가입일 범위·`includeWithdrawn`. `sort`: `id,desc`(기본)·`createdAt,asc|desc`·`nickname,asc|desc`.",
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회"), ApiResponse(responseCode = "400", description = "size>200·sort 오류"), ApiResponse(responseCode = "403", description = "AUTH-008")])
    fun getMembers(
        q: String?,
        email: String?,
        provider: SocialProvider?,
        memberStatus: MemberStatus?,
        onboardingCompleted: Boolean?,
        @Parameter(description = "가입일 시작(포함)") createdFrom: LocalDate?,
        @Parameter(description = "가입일 끝(포함)") createdTo: LocalDate?,
        includeWithdrawn: Boolean,
        sort: String,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminMemberListResponse>>

    @Operation(summary = "회원 상세", description = "외부 계정 식별자는 제공하지 않고 이메일은 마스킹한다. 스캔 크레딧·랭킹(점수·다음 등급)·활동 집계(실제 행 수)·최근 스캔/리뷰/주문 5건·제재 사유/시각 포함. 탈퇴 회원도 조회된다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회"), ApiResponse(responseCode = "400", description = "없는 회원(MEMBER-003)")])
    fun getMember(id: Long): ResponseEntity<BaseResponse<AdminMemberDetailResponse>>

    @Operation(summary = "랭킹 점수 변동 내역", description = "리뷰 작성/삭제로 인한 점수 변동 원장(최신순 페이지).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회")])
    fun getRankingEvents(id: Long, page: Int, size: Int): ResponseEntity<BaseResponse<AdminRankingEventPageResponse>>

    @Operation(summary = "회원 상태 변경", description = "SUSPENDED(사유 필수) 또는 ACTIVE. 정지 회원의 로그인·회원 API 는 403(MEMBER-012). 같은 상태로 재요청하면 200 멱등.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "변경"), ApiResponse(responseCode = "400", description = "정지 사유 누락(COMMON-002)")])
    fun changeStatus(id: Long, request: AdminMemberStatusRequest, adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>>

    @Operation(summary = "프로필 초기화", description = "닉네임을 `사용자{id}` 로, 프로필 사진을 기본(null)으로 되돌린다. 둘 다 false 면 400.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "초기화"), ApiResponse(responseCode = "400", description = "COMMON-002")])
    fun resetProfile(id: Long, request: AdminMemberProfileResetRequest, adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>>

    @Operation(summary = "스캔 제한 해제", description = "무료 스캔 3회 소진 회원의 제한을 수동으로 푼다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "해제")])
    fun unlockScan(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>>

    @Operation(summary = "강제 탈퇴", description = "본인 탈퇴와 같은 절차 — 외부 계정 삭제 선행 후 탈퇴 처리. 외부 삭제 실패 시 500(AUTH-007) + 감사 `MEMBER_WITHDRAW_FAILED`. 이미 탈퇴면 200 멱등.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "탈퇴"), ApiResponse(responseCode = "500", description = "소셜 계정 삭제 실패(AUTH-007)")])
    fun withdraw(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminMemberActionResponse>>
}
