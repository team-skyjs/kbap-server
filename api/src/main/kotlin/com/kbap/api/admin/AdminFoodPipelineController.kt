package com.kbap.api.admin

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthAdminId
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.ADMIN + "/foods", version = "1.0+")
class AdminFoodPipelineController(
    private val service: AdminFoodPipelineService,
) : AdminFoodPipelineApi {
    @PostMapping("/{id}/recollect")
    override fun recollectOne(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminRecollectOneResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.recollectOne(adminId, id)))

    @PostMapping("/recollect")
    override fun recollect(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: FoodContentStatus?,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodRecollectResult>> =
        ResponseEntity.ok(BaseResponse.ok(service.recollect(adminId, q, status)))

    @PostMapping("/{id}/image/regenerate")
    override fun regenerateImage(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminImageRegenerateResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.regenerateImage(adminId, id)))

    @PostMapping("/{id}/image/upload-url")
    override fun issueImageUploadUrl(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminImageUploadUrlRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminImageUploadUrlResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.issueImageUploadUrl(adminId, id, request.contentType!!, request.contentLength!!)))

    @PutMapping("/{id}/image")
    override fun replaceImage(
        @PathVariable id: Long,
        @Valid @RequestBody request: AdminImageReplaceRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodTransitionResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.replaceImage(adminId, id, request.objectKey!!.trim())))

    @GetMapping("/images")
    override fun getImageBatches(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<BaseResponse<AdminImageBatchPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.getImageBatchPage(AdminPaging.page(page), AdminPaging.size(size))))

    @GetMapping("/images/candidates/count")
    override fun countImageCandidates(): ResponseEntity<BaseResponse<AdminImageCandidateCountResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.countImageCandidates()))

    @GetMapping("/images/{batchId}")
    override fun getImageBatch(@PathVariable batchId: Long): ResponseEntity<BaseResponse<AdminImageBatchDetailResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.getImageBatchDetail(batchId)))

    @PostMapping("/images/collect")
    override fun collectImages(@AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminImageCollectResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.collectImagesNow(adminId)))

    @PostMapping("/images/items/resubmit")
    override fun resubmitItems(
        @Valid @RequestBody request: AdminImageResubmitRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminImageResubmitResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.resubmitItems(adminId, request.itemIds!!)))

    @GetMapping("/content-outboxes")
    override fun getContentOutboxes(
        @RequestParam(required = false) status: FoodContentOutboxStatus?,
        @RequestParam(required = false) foodId: Long?,
        @RequestParam(defaultValue = "3") stuckHours: Int,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminContentOutboxPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.getContentOutboxPage(status, foodId, stuckHours, AdminPaging.page(page), AdminPaging.size(size))))

    @PostMapping("/content-outboxes/{id}/requeue")
    override fun requeueContentOutbox(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminContentOutboxResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.requeueContentOutbox(adminId, id)))

    @PostMapping("/content-outboxes/{id}/cancel")
    override fun cancelContentOutbox(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminContentOutboxResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.cancelContentOutbox(adminId, id)))

    @GetMapping("/vector-outboxes")
    override fun getVectorOutboxes(
        @RequestParam(required = false) status: FoodVectorOutboxStatus?,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<BaseResponse<AdminVectorOutboxPageResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.getVectorOutboxPage(status, AdminPaging.page(page), AdminPaging.size(size))))

    @PostMapping("/vector-outboxes/enqueue")
    override fun enqueueVectors(@AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminVectorEnqueueResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.enqueueVectors(adminId)))

    @PostMapping("/vector-outboxes/{id}/retry")
    override fun retryVector(@PathVariable id: Long, @AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminVectorOutboxResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.retryVector(adminId, id)))

    @PostMapping("/vector-outboxes/retry-all-failed")
    override fun retryAllFailedVectors(@AuthAdminId adminId: Long): ResponseEntity<BaseResponse<AdminVectorRetryAllResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.retryAllFailedVectors(adminId)))

    @PostMapping("/bulk")
    override fun bulk(
        @Valid @RequestBody request: AdminFoodBulkRequest,
        @AuthAdminId adminId: Long,
    ): ResponseEntity<BaseResponse<AdminFoodBulkResponse>> =
        ResponseEntity.ok(BaseResponse.ok(service.bulk(adminId, request.action!!, request.ids!!)))
}
