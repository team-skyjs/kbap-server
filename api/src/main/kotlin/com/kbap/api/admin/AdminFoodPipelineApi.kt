package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 파이프라인 개입", description = "재수집·이미지 재생성/교체·이미지 배치·콘텐츠/벡터 아웃박스·일괄 작업 — 모든 조작은 감사 이력에 남는다")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodPipelineApi {
    @Operation(summary = "음식 1건 재수집", description = "해당 음식만 콘텐츠 수집 요청(PENDING)을 만든다. 이미 대기 중이면 `created:false` 와 기존 요청 id.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "요청 생성 또는 기존 반환"), ApiResponse(responseCode = "400", description = "없는 음식(FOOD-001)")])
    fun recollectOne(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminRecollectOneResponse>>

    @Operation(summary = "조건 일괄 재수집", description = "표시명 포함 검색어·상태로 걸린 음식 전부를 수집 요청한다(최대 500, 초과 시 `exceeded:true`). 이미 대기 중인 음식은 건너뛴다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "결과 카운트")])
    fun recollect(q: String?, status: FoodContentStatus?, adminId: Long): ResponseEntity<BaseResponse<AdminFoodRecollectResult>>

    @Operation(summary = "이미지 재생성", description = "상태를 유지한 채(READY 포함) 단건 이미지 생성 배치를 제출한다. 진행 중 아이템이 있으면 409(FOOD-009).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "제출"), ApiResponse(responseCode = "409", description = "진행 중(FOOD-009)")])
    fun regenerateImage(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminImageRegenerateResponse>>

    @Operation(summary = "이미지 업로드 URL 발급", description = "사전 서명 업로드 URL. 형식(UPLOAD-001)·크기(UPLOAD-003) 규칙은 회원 이미지 업로드와 같다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "발급"), ApiResponse(responseCode = "400", description = "형식·크기 위반")])
    fun issueImageUploadUrl(id: Long, request: AdminImageUploadUrlRequest, adminId: Long): ResponseEntity<BaseResponse<AdminImageUploadUrlResponse>>

    @Operation(summary = "이미지 교체", description = "업로드된 객체 키로 이미지를 교체한다. 저장소에 없으면 400(IMAGE-003), 이미지가 아니면 400(IMAGE-001). READY 는 유지되고 벡터 동기화가 예약된다. PENDING_IMAGE 였으면 PENDING_REVIEW 로.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "교체"), ApiResponse(responseCode = "400", description = "IMAGE-003·IMAGE-001")])
    fun replaceImage(id: Long, request: AdminImageReplaceRequest, adminId: Long): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>>

    @Operation(summary = "이미지 배치 목록", description = "최신순 페이지. 배치별 외부 식별자·프롬프트 버전·아이템 상태 카운트.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회")])
    fun getImageBatches(@Parameter(example = "1") page: Int, @Parameter(example = "20") size: Int): ResponseEntity<BaseResponse<AdminImageBatchPageResponse>>

    @Operation(summary = "이미지 제출 후보 수", description = "다음 `POST /api/admin/foods/images` 가 제출할 음식 수(PENDING_IMAGE ∧ 진행 중 아이템 없음).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회")])
    fun countImageCandidates(): ResponseEntity<BaseResponse<AdminImageCandidateCountResponse>>

    @Operation(summary = "이미지 배치 상세", description = "아이템별 음식·상태·파일명·실패 사유.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회"), ApiResponse(responseCode = "400", description = "없는 배치")])
    fun getImageBatch(batchId: Long): ResponseEntity<BaseResponse<AdminImageBatchDetailResponse>>

    @Operation(summary = "이미지 배치 즉시 회수", description = "정시 회수(3시간 cron)를 기다리지 않고 지금 회수한다. 스케줄과 같은 락을 쓰므로 겹치면 409(FOOD-008).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "회수 결과"), ApiResponse(responseCode = "409", description = "락 점유 중(FOOD-008)")])
    fun collectImages(adminId: Long): ResponseEntity<BaseResponse<AdminImageCollectResponse>>

    @Operation(summary = "실패 아이템 재제출", description = "아이템 id 들의 음식으로 새 배치를 만든다. 진행 중 아이템이 있으면 409(FOOD-009).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "제출"), ApiResponse(responseCode = "409", description = "FOOD-009")])
    fun resubmitItems(request: AdminImageResubmitRequest, adminId: Long): ResponseEntity<BaseResponse<AdminImageResubmitResponse>>

    @Operation(summary = "콘텐츠 수집 요청 목록", description = "상태·음식으로 거른 최신순 페이지. `stuck` = SENT 이고 발행 후 `stuckHours` 경과.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회")])
    fun getContentOutboxes(
        status: FoodContentOutboxStatus?,
        foodId: Long?,
        @Parameter(description = "고착 기준 시간", example = "3") stuckHours: Int,
        page: Int,
        size: Int,
    ): ResponseEntity<BaseResponse<AdminContentOutboxPageResponse>>

    @Operation(summary = "수집 요청 재발행", description = "SENT → PENDING(발행 시각 초기화). 다른 상태면 409(FOOD-010).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "재발행"), ApiResponse(responseCode = "409", description = "FOOD-010")])
    fun requeueContentOutbox(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminContentOutboxResponse>>

    @Operation(summary = "수집 요청 취소", description = "PENDING/SENT → CANCELED. 이후 도착하는 결과는 거절된다. 다른 상태면 409(FOOD-010).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "취소"), ApiResponse(responseCode = "409", description = "FOOD-010")])
    fun cancelContentOutbox(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminContentOutboxResponse>>

    @Operation(summary = "벡터 동기화 요청 목록", description = "상태로 거른 최신순 페이지.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "조회")])
    fun getVectorOutboxes(status: FoodVectorOutboxStatus?, page: Int, size: Int): ResponseEntity<BaseResponse<AdminVectorOutboxPageResponse>>

    @Operation(summary = "미적재 READY 벡터 적재 예약", description = "UPSERT 요청이 없는 READY 음식을 최대 500건 예약하고 예약 수·잔여 수를 돌려준다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "예약 결과")])
    fun enqueueVectors(adminId: Long): ResponseEntity<BaseResponse<AdminVectorEnqueueResponse>>

    @Operation(summary = "벡터 요청 재시도", description = "FAILED → PENDING. 다른 상태면 409(FOOD-010).")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "재시도"), ApiResponse(responseCode = "409", description = "FOOD-010")])
    fun retryVector(id: Long, adminId: Long): ResponseEntity<BaseResponse<AdminVectorOutboxResponse>>

    @Operation(summary = "실패 벡터 요청 일괄 재시도", description = "FAILED 전부를 PENDING 으로 되돌리고 건수를 돌려준다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "재시도 건수")])
    fun retryAllFailedVectors(adminId: Long): ResponseEntity<BaseResponse<AdminVectorRetryAllResponse>>

    @Operation(summary = "일괄 작업", description = "APPROVE·RECOLLECT·DELETE 를 최대 500건에 건별 독립 트랜잭션으로 수행하고 건별 성공/실패 코드를 돌려준다.")
    @ApiResponses(value = [ApiResponse(responseCode = "200", description = "건별 결과"), ApiResponse(responseCode = "400", description = "500건 초과(COMMON-002)")])
    fun bulk(request: AdminFoodBulkRequest, adminId: Long): ResponseEntity<BaseResponse<AdminFoodBulkResponse>>
}
