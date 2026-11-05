package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.llm.MenuNameKind
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 메뉴 이름 추정", description = "관리자 전용 — 메뉴판 스캔 LLM 추정 캐시 관리(S-959)")
@SecurityRequirement(name = "bearerAuth")
interface AdminMenuNameEstimateApi {
    @Operation(
        summary = "AI 추정 메뉴 목록(S-966)",
        description = """
            메뉴판 스캔에서 DB 에 없던 메뉴의 LLM 추정 행(menu_name_estimate)과, 같은 이름 키 음식의 상태를 함께 준다.
            스캔은 미등록 이름마다 미완성 음식(FAILED)을 만들고 콘텐츠를 요청하므로 대부분 food 가 있다 — food=null 은 그 음식이 소프트 삭제된 경우다(deletedFoodId).
            scanCount 는 같은 키 음식의 스캔 이력(scan_history) 건수다. 정렬 기본은 스캔 많은 순(같으면 최근 id 순).
            승격 뒤에도 행은 목록에 남고 promotedAt·promotable 만 바뀐다(미승격만 보려면 promoted=false).
            - **ADMIN 역할 JWT 전용**. 읽기라 X-Target-Environment 는 받지 않는다.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST)
    fun getEstimates(
        @Parameter(description = "0부터", example = "0") page: Int,
        @Parameter(description = "1~100", example = "50") size: Int,
        @Parameter(description = "SCAN_COUNT(스캔 많은 순, 기본)·RECENT(추정 생성 최신 순)") sort: AdminMenuNameEstimateSort,
        @Parameter(description = "DISH·DRINK·NOT_FOOD") kind: MenuNameKind?,
        @Parameter(description = "true=승격한 것만, false=미승격만") promoted: Boolean?,
        @Parameter(description = "같은 키 음식 상태 — NONE 은 활성 음식 없음") foodStatus: AdminMenuNameEstimateFoodStatus?,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimatePage>>

    @Operation(
        summary = "AI 추정 메뉴를 음식으로 등록(승격, S-966)",
        description = """
            운영자가 고른 추정 이름을 정식 음식 파이프라인에 넣는다(자동 승격 없음). 추정 행 → 같은 키 음식 순으로 잠근다.
            - 같은 키 음식이 없으면(소프트 삭제 등) 미완성 음식을 만들고 콘텐츠를 요청한다 → CREATED_FOOD. 표시 이름은 이름 키다.
            - 준비 전 음식이면 수동 재수집과 같은 경로로 콘텐츠를 다시 요청한다 → CONTENT_REQUESTED. 대기 중인 요청이 있으면 겹쳐 쌓지 않는다 → ALREADY_PENDING.
            - 승격 시각·어드민 id 를 추정 행에 남긴다. 한 추정은 한 번만 승격한다.
            - **ADMIN 역할 JWT 전용**. X-Target-Environment(dev|prod) 필수 — 서버 환경과 다르면 409 FOOD-026.
        """,
    )
    @ApiErrors(
        ErrorCode.MENU_NAME_ESTIMATE_NOT_FOUND,
        ErrorCode.MENU_NAME_ESTIMATE_ALREADY_PROMOTED,
        ErrorCode.FOOD_ALREADY_READY,
        ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT,
        ErrorCode.FOOD_ENVIRONMENT_MISMATCH,
    )
    fun promoteEstimate(
        @Parameter(description = "추정 행 id(목록의 id)") id: Long,
        @Parameter(description = "dev 또는 prod — 서버 환경과 같아야 한다") target: String,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminMenuNameEstimatePromoteResult>>

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
