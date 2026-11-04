package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 메뉴 이름 추정", description = "관리자 전용 — 메뉴판 스캔 LLM 추정 캐시 관리(S-959)")
@SecurityRequirement(name = "bearerAuth")
interface AdminMenuNameEstimateApi {
    @Operation(
        summary = "메뉴 이름 추정 캐시 삭제(재추정)",
        description = """
            이름 키 하나의 추정 행을 지운다. 다음 스캔에서 그 이름을 다시 추정한다(잘못된 NOT_FOOD·코드 판정을 고칠 때).
            경로 값은 matchKey 로 정규화한다(한글만 남김). 없어도 200(deleted=false).
            menu_name_estimate 는 재생성 가능한 파생 캐시라 행을 실제로 지운다(소프트 삭제하면 name_key 유니크 때문에 재추정 저장이 막힌다).
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    fun deleteEstimate(
        @Parameter(description = "메뉴 이름 또는 이름 키", example = "갈비탕") nameKey: String,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimateDeleteResponse>>
}
