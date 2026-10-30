package com.kbap.api.admin

import com.kbap.api.curation.*
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 서울 큐레이션", description = "활성 ADMIN 전용. 모든 mutation은 X-Target-Environment dev|prod와 서버환경 대조. 기대버전충돌409/currentVersion. photos 권리는 NONE만 게시 허용.")
@SecurityRequirement(name = "bearerAuth")
interface AdminCurationApi {
    @Operation(summary = "전체 게시 상태와 모음별 검증 요약")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun getOverview(@Parameter(hidden = true) adminId: Long): ResponseEntity<BaseResponse<CurationAdminOverview>>

    @Operation(summary = "초안·게시본·버전과 현재 문서 requiredContentHash 조회")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun getDetail(@Parameter(hidden = true) adminId: Long, collectionKey: String): ResponseEntity<BaseResponse<CurationAdminDetail>>

    @Operation(summary = "초안 전체 교체. 불완전 초안 허용. 검수는 현재 문서 SHA256과 checkedOn을 editorialReview로 제출")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun updateDraft(@Parameter(hidden = true) adminId: Long, collectionKey: String, target: String, request: CurationDraftRequest): ResponseEntity<BaseResponse<CurationAdminDetail>>

    @Operation(summary = "저장 초안 검증. valid=false도200. 쓰기 없음")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun validate(@Parameter(hidden = true) adminId: Long, collectionKey: String, target: String, request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationValidationResult>>

    @Operation(summary = "10항목·10언어·READY사진·NONE권리·현재 검수 확인 후 원자 게시. 최초3모음 latch")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun publish(@Parameter(hidden = true) adminId: Long, collectionKey: String, target: String, request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationAdminDetail>>

    @Operation(summary = "공개 내림. 마지막 게시본과 최초 latch 보존")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun unpublish(@Parameter(hidden = true) adminId: Long, collectionKey: String, target: String, request: CurationVersionRequest): ResponseEntity<BaseResponse<CurationAdminDetail>>

    @Operation(summary = "ADMIN 사진 예약. JPEG/PNG/WebP 10MiB, presign5분. collection version 불변. S3요청에는 API토큰/환경/버전헤더를 보내지 않는다")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun createUpload(@Parameter(hidden = true) adminId: Long, collectionKey: String, target: String, request: CurationUploadRequest): ResponseEntity<BaseResponse<CurationUploadResponse>>

    @Operation(summary = "발급 관리자만 확정. 발급만료+1일 고정기한, HEAD/조건부복사/DB채택. 재시도동일READY. 사진첨부는 별도 초안저장")
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_NOT_FOUND, ErrorCode.CURATION_PUBLICATION_INVALID, ErrorCode.CURATION_VERSION_CONFLICT, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_ASSET_INVALID, ErrorCode.CURATION_UNAVAILABLE)
    fun complete(@Parameter(hidden = true) adminId: Long, collectionKey: String, assetId: Long, target: String, request: CurationCompleteRequest): ResponseEntity<BaseResponse<CurationCompleteResponse>>

    @Operation(
        summary = "표시용 변형(폭 1080 WebP) 백필 — 변형 없는 READY 자산에 1회성으로 만든다(KB-930)",
        description = "dryRun=true(기본)면 대상 수만 센다. 실행하면 대상을 id 순으로 최대 limit(1~500, 기본 100)개 처리하고, 변환·저장 실패는 failedAssetIds 로 돌려주며 원본은 그대로 둔다(응답은 원본 폴백). " +
            "originalBytes·displayBytes 는 이번에 변환한 자산들의 원본·변형 바이트 합이다. 다시 불러도 이미 변형이 있는 자산은 건너뛴다. " +
            "업로드 확정(complete)도 같은 변형을 만든다. 변형 키는 {원본 키}-w1080.webp, Cache-Control immutable.",
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.ADMIN_FORBIDDEN, ErrorCode.CURATION_ENVIRONMENT_MISMATCH, ErrorCode.CURATION_UNAVAILABLE)
    fun backfillDisplayVariants(@Parameter(hidden = true) adminId: Long, target: String, request: CurationDisplayBackfillRequest): ResponseEntity<BaseResponse<CurationDisplayBackfillResult>>

}
