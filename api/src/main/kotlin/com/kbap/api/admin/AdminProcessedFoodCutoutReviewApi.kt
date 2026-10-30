package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.processedfood.model.CutoutReviewStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import java.math.BigDecimal

@Tag(name = "관리자 가공식품 누끼 검수", description = "관리자 전용 — 일괄 등록한 누끼(PENDING)를 검수해 승인분만 앱 imageUrl 에 쓴다")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodCutoutReviewApi {
    @Operation(
        summary = "누끼 일괄 등록",
        description = """
            S3 에 이미 PUT 된 누끼 PNG 를 최대 500건씩 등록한다. 키는 제품별 고정 `{key-prefix}/processed/cutout/{품목보고번호}.png`.
            항목마다 S3 HEAD 로 존재·image/png·2MB 이하를 확인하고(최대 8개 병렬) `cutout_image_url` 을 저장한 뒤 **PENDING** 으로 둔다 — 승인 전에는 앱 imageUrl 이 HACCP 원본이다.
            부분 성공: 잘못된 항목만 `failures` 에 productId·사유로 돌려주고 나머지는 저장한다.
            멱등: 같은 오브젝트(같은 수정 시각)를 다시 등록하면 검수 상태를 유지하고 route·coverage 만 갱신한다. 오브젝트를 다시 올렸으면(수정 시각이 다름) PENDING 으로 돌아간다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "등록(부분 성공 포함)"),
            ApiResponse(responseCode = "400", description = "빈 요청·500건 초과(COMMON-002)"),
        ],
    )
    fun bulkRegister(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "등록 항목 배열(1~500건)")
        items: List<AdminCutoutRegisterItem>,
    ): ResponseEntity<BaseResponse<AdminCutoutBulkRegisterResponse>>

    @Operation(
        summary = "누끼 검수 목록",
        description = """
            누끼가 등록된 제품을 id 오름차순으로 준다. 상태·경로·전경 비율(이상·이하)로 거르고, `nextCursor` 를 `cursor` 로 넘겨 다음 쪽을 받는다.
            `sort=RECENT` 면 검수 시각 최신순(같으면 id 큰 순)이다 — **status 가 APPROVED·REJECTED 일 때만** 적용하고, PENDING·상태 없음이면 무시하고 id 오름차순이다(PENDING 은 검수 시각이 없다).
            `q` 는 제품명 부분 일치(대소문자 무시)이고, 숫자만이면 품목보고번호·가공식품 id 정확 일치도 함께 찾는다. 다른 필터와 AND 다.
            커서는 불투명 문자열이다 — 기본 정렬은 id 숫자 문자열(기존 숫자 커서도 그대로 받는다), RECENT 는 정렬 값을 담은 문자열이라 같은 sort·필터로만 이어 받는다.
            항목마다 HACCP 원본 URL·누끼 URL·앱이 받는 imageUrl·경로·전경 비율·검수 기록을 싣는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "조회"),
            ApiResponse(responseCode = "400", description = "잘못된 status·sort 값(COMMON-002) · 커서 형식 오류(FOOD-002)"),
        ],
    )
    fun getCutoutReviewPage(
        @Parameter(description = "PENDING·APPROVED·REJECTED — 없으면 전부") status: CutoutReviewStatus?,
        @Parameter(description = "vision·fallback — 없으면 전부") route: String?,
        @Parameter(description = "전경 비율 하한(이상)") minCoverage: BigDecimal?,
        @Parameter(description = "전경 비율 상한(이하)") maxCoverage: BigDecimal?,
        @Parameter(description = "이전 쪽의 nextCursor(불투명 문자열)") cursor: String?,
        @Parameter(description = "쪽 크기(1~200, 기본 50)") size: Int,
        @Parameter(description = "RECENT = 검수 시각 최신순(status 가 APPROVED·REJECTED 일 때만, 그 밖엔 무시)") sort: AdminCutoutReviewSort?,
        @Parameter(description = "검색어 — 제품명 부분 일치(대소문자 무시), 숫자만이면 품목보고번호·id 정확 일치도") q: String?,
    ): ResponseEntity<BaseResponse<AdminCutoutReviewPage>>

    @Operation(
        summary = "누끼 한 장 검수",
        description = """
            `approved=true` 면 APPROVED(앱 imageUrl 이 누끼), false 면 REJECTED(앱은 HACCP 원본)로 바꾸고 검수자·시각을 남긴다.
            반려 사유 `reason` 은 반려일 때만 저장하며 승인하면 지운다. 이미 검수한 것도 다시 검수할 수 있다.
            `expectedCutoutImageUrl`(화면에서 본 누끼 URL)을 보내면 행을 잠그고 현재 누끼와 대조해, 그사이 다시 등록돼 다르면 바꾸지 않고 409 PFOOD-010 을 준다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "검수"),
            ApiResponse(responseCode = "400", description = "approved 누락·사유 200자 초과(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001) · 누끼 없음(PFOOD-009)"),
            ApiResponse(responseCode = "409", description = "본 누끼와 현재 누끼가 다름(PFOOD-010) — 목록을 새로 받아 다시 검수"),
        ],
    )
    fun review(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "승인 여부와 반려 사유")
        request: AdminCutoutReviewRequest,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminCutoutReviewItem>>

    @Operation(
        summary = "누끼 조건 일괄 승인",
        description = """
            `PENDING` 이면서 `route=vision` 이고 전경 비율이 `minCoverage` 이상인 누끼를 한 번에 승인하고 건수를 준다.
            `dryRun=true` 면 바꾸지 않고 승인될 건수만 센다. fallback 경로·반려·이미 승인된 것은 건드리지 않는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "승인(또는 dryRun 집계)"),
            ApiResponse(responseCode = "400", description = "minCoverage 누락·0~1 밖(COMMON-002)"),
        ],
    )
    fun bulkApprove(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "전경 비율 하한과 dryRun")
        request: AdminCutoutBulkApproveRequest,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminCutoutBulkApproveResponse>>
}
