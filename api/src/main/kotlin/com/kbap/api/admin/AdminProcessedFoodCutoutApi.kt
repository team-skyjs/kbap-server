package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 누끼 이미지", description = "관리자 전용 — 배경을 뗀 제품 이미지(투명 PNG) 업로드·해제")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodCutoutApi {
    @Operation(
        summary = "누끼 이미지 업로드 URL 발급",
        description = """
            키 `{key-prefix}/processed/cutout/{품목보고번호}.png` 로 PUT 할 presigned URL(10분)을 준다. Content-Type 은 image/png 고정,
            `contentLength` 는 서명에 들어가므로 실제 파일 크기와 같아야 한다(최대 2MB). 같은 제품은 늘 같은 키라 다시 올리면 덮어쓴다.
            올린 뒤 `POST .../confirm` 으로 저장해야 앱에 반영된다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급"),
            ApiResponse(responseCode = "400", description = "contentLength 누락·0 이하(COMMON-002) · 2MB 초과(UPLOAD-003)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun presign(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "올릴 PNG 바이트 수")
        request: AdminProcessedFoodCutoutPresignRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutPresignResponse>>

    @Operation(
        summary = "누끼 이미지 저장(업로드 확인)",
        description = """
            S3 에 오브젝트가 있고 image/png · 2MB 이하인지 확인한 뒤 `cutout_image_url` 에 공개 URL(`?v=수정시각`)을 저장한다.
            이후 목록·상세·이름 스캔 후보·대표 상품·리뷰 카드의 `imageUrl` 이 누끼를 우선한다(HACCP 원본 `image_url` 은 그대로, 출처 표기 유지).
            같은 키로 다시 확인해도 된다(멱등). HACCP 미러 동기화는 이 값을 건드리지 않는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "저장"),
            ApiResponse(responseCode = "400", description = "이 제품의 키가 아님(COMMON-002) · 오브젝트 없음·PNG 아님·2MB 초과·확인 실패(PFOOD-008)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun confirm(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "presign 응답의 key")
        request: AdminProcessedFoodCutoutConfirmRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutResponse>>

    @Operation(
        summary = "누끼 이미지 해제",
        description = """
            `cutout_image_url` 을 비워 `imageUrl` 이 HACCP 원본으로 돌아간다. 없을 때 불러도 200(멱등). S3 오브젝트는 지우지 않는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "해제"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun delete(
        @Parameter(description = "가공식품 id") id: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutResponse>>
}
