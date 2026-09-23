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
    @ApiErrors(ErrorCode.FOOD_NOT_FOUND, ErrorCode.IMAGE_BATCH_IN_PROGRESS, ErrorCode.FOOD_STATUS_NOT_READY)
    fun regenerateImage(
        @Parameter(description = "음식 id", required = true, example = "42") foodId: Long,
        request: AdminFoodImageRegenerateRequest?,
    ): ResponseEntity<BaseResponse<AdminFoodImageRegenerateResponse>>
}
