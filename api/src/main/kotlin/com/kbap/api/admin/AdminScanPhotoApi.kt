package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 스캔 사진", description = "관리자 전용 — 모든 회원의 스캔 사진 피드")
@SecurityRequirement(name = "bearerAuth")
interface AdminScanPhotoApi {
    @Operation(
        summary = "전체 스캔 사진 피드",
        description = """
            모든 회원의 저장된 스캔 사진을 최신순(createdAt, kind, 행 id 내림차순) 24장씩 커서로 조회한다(KB-879).
            - 출처: ORDER = 단일 스캔 주문 사진, BATCH = 여러 장 묶음 스캔 사진(묶음 주문의 대표 사진도 BATCH 로만).
            - 출처 행 단위다 — 같은 업로드 사진이 서로 다른 묶음 두 개에 쓰이면 2건이다(회원별 갤러리는 경로로 중복 제거).
            - 총 건수는 주지 않는다 — hasNext 와 nextCursor 로 다음 페이지를 읽는다. 잘못된 cursor 는 400(FOOD-002).
            - 탈퇴·정지 회원 사진도 포함한다(memberStatus). 분석 보고서용 회원 제외 규칙은 적용하지 않는다.
            - **ADMIN 역할 JWT 전용** — USER 토큰은 403(AUTH-008).
        """,
    )
    fun getScanPhotoFeed(
        @Parameter(description = "true 면 봇 계정 사진만, 없거나 false 면 봇 제외") isBot: Boolean,
        @Parameter(description = "이 회원 사진만") memberId: Long?,
        @Parameter(description = "이전 응답의 nextCursor") cursor: String?,
    ): ResponseEntity<BaseResponse<AdminScanPhotoFeed>>
}
