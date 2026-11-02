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
            키 `{key-prefix}/processed/cutout/{품목보고번호}-{sha256 앞 16자}.png` 로 PUT 할 presigned URL(10분)을 준다. Content-Type 은 image/png 고정,
            `contentLength` 는 서명에 들어가므로 실제 파일 크기와 같아야 한다(최대 2MB).
            `contentSha256`(파일 SHA-256 소문자 hex 64자)을 받아 S3 체크섬(`x-amz-checksum-sha256`)을 서명에 넣는다 — `requiredHeaders` 를 그대로 붙여 PUT 하면 되고,
            본문 해시가 다르면 S3 가 PUT 을 거절한다. 같은 그림은 같은 키(멱등), 그림이 바뀌면 키가 바뀐다.
            올린 뒤 `POST .../confirm` 으로 저장해야 앱에 반영된다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급"),
            ApiResponse(responseCode = "400", description = "contentLength·contentSha256 누락, contentSha256 이 hex 64자가 아님(COMMON-002) · 2MB 초과(UPLOAD-003)"),
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
            S3 에 오브젝트가 있고 image/png · 2MB 이하이며, S3 SHA-256 체크섬 앞 16자가 키 안의 해시와 같은지 확인한 뒤 `cutout_image_url` 에 공개 URL(`{base}/{key}`)을 저장한다.
            키는 해시 키(`{품목보고번호}-{sha256 앞 16자}.png`)만 받는다 — 구 키(`{품목보고번호}.png`)는 COMMON-002.
            관리자가 직접 올린 한 장이므로 검수 상태는 바로 **APPROVED**(검수자 = 호출 관리자)다 — 일괄 등록(PENDING)과 다르다.
            이후 목록·상세·이름 스캔 후보·대표 상품·리뷰 카드의 `imageUrl` 이 누끼를 우선한다(HACCP 원본 `image_url` 은 그대로, 출처 표기 유지).
            같은 키로 다시 확인해도 된다(멱등). HACCP 미러 동기화는 이 값을 건드리지 않는다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "저장"),
            ApiResponse(responseCode = "400", description = "이 제품의 해시 키가 아님·구 키(COMMON-002) · 오브젝트 없음·PNG 아님·2MB 초과·체크섬 없음·체크섬 불일치·확인 실패(PFOOD-008)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun confirm(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "presign 응답의 key")
        request: AdminProcessedFoodCutoutConfirmRequest,
        adminId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutResponse>>

    @Operation(
        summary = "누끼 이미지 해제",
        description = """
            `cutout_image_url` 과 검수 상태·경로·전경 비율·검수 기록을 비워 `imageUrl` 이 HACCP 원본으로 돌아간다. 없을 때 불러도 200(멱등). S3 오브젝트는 지우지 않는다.
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
