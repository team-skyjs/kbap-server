package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 사진", description = "관리자 전용 — 상품당 추가 사진 여러 장(앞면·윗면·성분표 등) 업로드·순서 변경·삭제(KB-965). 누끼는 cutout-image API")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodImageApi {
    @Operation(summary = "가공식품 사진 목록", description = "추가 사진을 표시 순서(sort_order)대로 준다. 기존 HACCP 원본·누끼 컬럼은 포함하지 않는다.\n- **ADMIN 역할 JWT 전용**.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "목록"), ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)")])
    fun getImages(@Parameter(description = "가공식품 id") id: Long): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>>

    @Operation(
        summary = "가공식품 사진 업로드 URL 발급",
        description = """
            키 `{key-prefix}/processed/images/{품목보고번호}-{sha256 앞 16자}.{png|jpg|webp}` 로 PUT 할 presigned URL(10분)을 준다.
            `contentType` 은 image/png · image/jpeg · image/webp, `contentLength` 는 실제 파일 크기(최대 5MB)다.
            `requiredHeaders` 를 그대로 붙여 PUT 하고 `POST .../confirm` 으로 저장해야 앱에 반영된다. 누끼 단일 업로드(cutout-image)와 같은 방식이다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "발급"),
            ApiResponse(responseCode = "400", description = "지원하지 않는 contentType·contentSha256 형식 오류(COMMON-002) · 5MB 초과(UPLOAD-003)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun presign(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "올릴 파일 정보")
        request: AdminProcessedFoodImagePresignRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodCutoutPresignResponse>>

    @Operation(
        summary = "가공식품 사진 저장(업로드 확인)",
        description = """
            S3 오브젝트가 있고 Content-Type 이 키 확장자와 맞고 5MB 이하이며 SHA-256 앞 16자가 키 해시와 같으면, 맨 뒤 순서로 추가한다.
            같은 사진(같은 키)을 다시 확인해도 한 장이다(멱등). 앱 상세 `images` 는 대표 사진(승인 누끼 또는 HACCP 원본)이 먼저, 그다음 이 순서다.
            누끼는 검수 상태가 있는 누끼 API(`cutout-image`)로 올린다 — 여기서 kind=CUTOUT 은 COMMON-002 다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "저장 — 저장 뒤 사진 목록"),
            ApiResponse(responseCode = "400", description = "이 제품의 해시 키가 아님·kind 누락·kind=CUTOUT(COMMON-002) · 오브젝트 없음·형식/크기/체크섬 불일치(PFOOD-015)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun confirm(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "presign 응답의 key 와 사진 종류")
        request: AdminProcessedFoodImageConfirmRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>>

    @Operation(summary = "가공식품 사진 순서 변경", description = "이 상품 사진 id 전부를 새 순서로 받아 sort_order 를 0부터 다시 매긴다.\n- **ADMIN 역할 JWT 전용**.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "변경 — 변경 뒤 사진 목록"),
            ApiResponse(responseCode = "400", description = "빠지거나 남는 id·중복(COMMON-002)"),
            ApiResponse(responseCode = "404", description = "가공식품 없음(PFOOD-001)"),
        ],
    )
    fun reorder(
        @Parameter(description = "가공식품 id") id: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "새 순서의 사진 id 목록")
        request: AdminProcessedFoodImageOrderRequest,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>>

    @Operation(summary = "가공식품 사진 삭제", description = "사진 한 장을 목록에서 뺀다(소프트 삭제, S3 오브젝트는 지우지 않는다).\n- **ADMIN 역할 JWT 전용**.")
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "삭제 — 삭제 뒤 사진 목록"),
            ApiResponse(responseCode = "404", description = "이 상품의 사진이 아니거나 이미 삭제됨(PFOOD-016)"),
        ],
    )
    fun delete(
        @Parameter(description = "가공식품 id") id: Long,
        @Parameter(description = "사진 id") imageId: Long,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodImagesResponse>>
}
