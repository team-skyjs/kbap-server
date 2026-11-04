package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "어드민 - 음식 이미지", description = "음식 이미지 갤러리 조회·대표 교체·재생성")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodImageApi {
    @Operation(
        summary = "이미지 갤러리 조회",
        description = """
            음식이 가진 이미지를 전부 내려준다. 대표(isPrimary=true)가 항상 첫 번째이고 이후는 정렬 순서(sortOrder, id)다.
            소프트 삭제된 이미지는 제외되며 한 장도 없으면 items 는 빈 배열이다.
            version 은 음식 낙관잠금 버전으로, 대표 교체 요청 바디에 그대로 되돌려 보낸다.
        """,
    )
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회 성공")])
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND)
    fun getGallery(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>>

    @Operation(
        summary = "대표 이미지 교체",
        description = """
            지정한 이미지를 대표로 올리고 기존 대표를 내린다. 음식의 image_ref 도 같은 키로 맞추고 벡터 재색인을 예약한다.
            이미 대표인 이미지를 다시 지정하면 아무것도 바꾸지 않고 200 이다(멱등).
            **대표 지정은 이 API 가 유일한 경로다** — 음식 수정 PUT 으로 imageRef 를 직접 바꿀 수 없다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "교체 성공 — 갱신된 갤러리(version 증가)"),
            ApiResponse(responseCode = "404", description = "음식이 없거나 그 음식의 이미지가 아님"),
            ApiResponse(responseCode = "409", description = "다른 관리자가 먼저 수정(FOOD-006)"),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_IMAGE_NOT_FOUND, ErrorCode.FOOD_VERSION_CONFLICT)
    fun setPrimary(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
        @Parameter(description = "대표로 지정할 이미지 id", required = true, example = "7") imageId: Long,
        request: AdminFoodImagePrimaryRequest,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>>

    @Operation(
        summary = "이미지 재생성",
        description = """
            대표 이미지를 새로 생성하도록 배치에 제출한다. 음식은 PENDING_IMAGE 로 내려가 **이미지가 붙을 때까지 앱에서 사라진다**(의도).
            같은 트랜잭션에서 벡터 삭제를 예약하고, 생성이 끝나면 기존 파이프라인이 다시 색인한다.
            이미 생성이 진행 중이면 409(IMAGE-004), READY 가 아닌 음식이면 409(FOOD-011)다.
            콘텐츠 재수집이 진행 중(아웃박스 PENDING·SENT)이면 409(FOOD-020) — 재수집 결과가 재생성 상태를 흔들지 않게 상호 배제한다.
            요청 본문의 intent 는 **생성 실패 시 처리만** 가른다 — REPLACE_BETTER 는 옛 이미지로 READY 복원, WRONG_IMAGE 는 숨김 유지.
            본문·intent 누락은 WRONG_IMAGE 와 같다(어드민 KB-621 배포 후 필수 전환 예정). REPLACE_BETTER 는 READY 음식에만 쓸 수 있다(FOOD-011).
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공"),
            ApiResponse(responseCode = "409", description = "이미 진행 중(IMAGE-004) 또는 READY 아님(FOOD-011)"),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.IMAGE_BATCH_IN_PROGRESS, ErrorCode.FOOD_STATUS_NOT_READY, ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT)
    fun regenerateImage(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
        request: AdminFoodImageRegenerateRequest?,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>>

    @Operation(
        summary = "후보 이미지 추가 생성",
        description = """
            대표 이미지와 공개 상태는 그대로 둔 채 후보 이미지를 **한 장 더** 생성해 갤러리에 **비대표로** 쌓는다.
            쓸지 말지는 관리자가 기존 "대표 지정" 으로 고른다. 프롬프트는 재생성과 같다.

            - READY 음식만(아니면 409 FOOD-011). 같은 음식에 추가 생성이 진행 중이면 409(FOOD-019).
            - 진행 중에는 갤러리·상세의 `additionalInProgress=true`, `regeneration` 은 영향받지 않는다(null 유지).
            - 수거되면 갤러리 맨 뒤(sortOrder 최댓값+1)에 비대표로 붙는다. 실패해도 음식 상태·대표는 변하지 않는다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "접수 성공 — contentStatus 는 READY 그대로"),
            ApiResponse(responseCode = "409", description = "READY 아님(FOOD-011) 또는 추가 생성 진행 중(FOOD-019)"),
        ],
    )
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_STATUS_NOT_READY, ErrorCode.FOOD_ADDITIONAL_IMAGE_IN_PROGRESS)
    fun generateAdditionalImage(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>>

    @Operation(
        summary = "음식 실물 사진 업로드 URL 발급",
        description = """
            키 `{key-prefix}/images/foods/{음식 id}/{sha256 앞 16자}.{png|jpg|webp}` 로 PUT 할 presigned URL(10분)을 준다 — 음식 id 접두가 고정이라 다른 음식 키는 confirm 에서 거절된다.
            `contentType` 은 image/png · image/jpeg · image/webp, `contentLength` 는 실제 파일 크기(최대 5MB), `contentSha256` 으로 S3 체크섬을 서명에 넣는다.
            `requiredHeaders` 를 그대로 붙여 PUT 한 뒤 `POST .../photos/confirm` 으로 저장해야 갤러리에 붙는다(가공식품 누끼 업로드와 같은 방식).
            - **ADMIN 역할 JWT 전용**, `X-Target-Environment` 가 서버 환경과 같아야 한다.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.IMAGE_TOO_LARGE, ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
    fun presignPhoto(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
        @Parameter(description = "대상 환경(dev·prod) — 서버 환경과 다르면 409") target: String,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "올릴 파일 정보")
        request: AdminFoodPhotoPresignRequest,
    ): ResponseEntity<BaseResponse<AdminFoodPhotoPresignResponse>>

    @Operation(
        summary = "음식 실물 사진 저장(업로드 확인)",
        description = """
            S3 오브젝트가 있고 Content-Type 이 키 확장자와 맞고 5MB 이하이며 SHA-256 앞 16자가 키 해시와 같으면 food_image 에 source=UPLOADED 로 맨 뒤 순서에 추가한다.
            같은 키를 다시 확인해도 한 장이다(멱등). `setPrimary=true` 면 그 사진을 유일한 대표로 바꾸고 `image_ref` 를 교체하며 벡터 갱신을 쌓는다(대표 지정 API 와 같은 규칙, 버전 확인 없음).
            콘텐츠 상태(contentStatus)는 바꾸지 않는다. 응답은 저장 뒤 갤러리다.
            - **ADMIN 역할 JWT 전용**, `X-Target-Environment` 가 서버 환경과 같아야 한다.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.FOOD_PHOTO_NOT_UPLOADED, ErrorCode.FOOD_NOT_FOUND, ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
    fun confirmPhoto(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
        @Parameter(description = "대상 환경(dev·prod) — 서버 환경과 다르면 409") target: String,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "presign 응답의 key 와 대표 지정 여부")
        request: AdminFoodPhotoConfirmRequest,
    ): ResponseEntity<BaseResponse<AdminFoodImageGalleryResponse>>
}
