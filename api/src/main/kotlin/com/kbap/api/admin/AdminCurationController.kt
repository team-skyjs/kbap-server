package com.kbap.api.admin

import com.kbap.api.curation.*
import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.JwtAuthenticationFilter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping(ApiPaths.ADMIN + "/curations/seoul", version = "1.0+")
class AdminCurationController(private val service: CurationService, private val assets: CurationAssetService) : AdminCurationApi {
    @GetMapping("")
    override fun getOverview(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long): ResponseEntity<BaseResponse<CurationAdminOverview>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.getOverview(adminId)))

    @GetMapping("/{collectionKey}")
    override fun getDetail(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String): ResponseEntity<BaseResponse<CurationAdminDetail>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.getAdminDetail(adminId, collectionKey)))

    @PutMapping("/{collectionKey}/draft")
    override fun updateDraft(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationDraftRequest): ResponseEntity<BaseResponse<CurationAdminDetail>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.updateDraft(adminId, target, collectionKey, request.expectedVersion!!, request.document)))

    @PostMapping("/{collectionKey}/validate")
    override fun validate(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationValidationResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.validate(adminId, target, collectionKey, request.expectedVersion!!)))

    @PostMapping("/{collectionKey}/publish")
    override fun publish(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationAdminDetail>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.publish(adminId, target, collectionKey, request.expectedVersion!!)))

    @PostMapping("/{collectionKey}/unpublish")
    override fun unpublish(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationAdminDetail>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(service.unpublish(adminId, target, collectionKey, request.expectedVersion!!)))

    @PostMapping("/{collectionKey}/assets/upload-url")
    override fun createUpload(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationUploadRequest): ResponseEntity<BaseResponse<CurationUploadResponse>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(assets.createUpload(adminId, target, collectionKey, request.expectedVersion!!, request.contentType, request.contentLength)))

    @PostMapping("/{collectionKey}/assets/{assetId}/complete")
    override fun complete(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @PathVariable collectionKey: String, @PathVariable assetId: Long, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationCompleteRequest): ResponseEntity<BaseResponse<CurationCompleteResponse>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(assets.complete(adminId, target, collectionKey, assetId, request.contentType, request.size)))

    @PostMapping("/assets/display-variants/backfill")
    override fun backfillDisplayVariants(@RequestAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) adminId: Long, @RequestHeader("X-Target-Environment") target: String, @Valid @RequestBody request: CurationDisplayBackfillRequest): ResponseEntity<BaseResponse<CurationDisplayBackfillResult>> =
        ResponseEntity.ok().header("Cache-Control", "no-store").body(BaseResponse.ok(assets.backfillDisplayVariants(adminId, target, request.dryRun, request.limit, request.afterAssetId)))

}
