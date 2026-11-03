package com.kbap.api.admin

import com.kbap.api.reviewbot.ReviewBotProcessedFoodComposeResult
import java.time.OffsetDateTime
import jakarta.validation.constraints.Size
import com.kbap.api.reviewbot.ReviewBotPhotoDedupeResult
import com.kbap.api.reviewbot.ReviewBotProcessedFoodRunResult
import com.kbap.api.reviewbot.ReviewBotRedistributeResult
import com.kbap.api.reviewbot.ReviewBotRewriteResult
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.DecimalMax
import com.kbap.api.core.BaseResponse
import com.kbap.api.reviewbot.ReviewBotAccountsResult
import com.kbap.api.reviewbot.ReviewBotPhotoSyncResult
import com.kbap.api.reviewbot.ReviewBotPhotoInventory
import com.kbap.api.reviewbot.ReviewBotPhotoHold
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 리뷰 봇", description = "관리자 전용 — 리뷰가 빈 READY 음식에 리뷰를 채우는 봇 계정 관리")
interface AdminReviewBotApi {
    @Operation(
        summary = "리뷰 봇 계정 보장(멱등)",
        description = """
            활성 봇 계정이 count 개가 되도록 **모자란 수만** 만든다. 이미 count 이상이면 아무것도 만들지 않는다.

            - 봇은 `is_bot=1`, 국가는 방한 상위국 가중(JP·TW·US·CN·TH 등), 닉네임은 서버가 정한다(사람 이름형 소문자, 봇끼리 중복 없음).
            - provider_uid 는 `review-bot:<uuid>` 합성값이라 소셜 로그인으로 들어올 수 없고, 기기 등록이 없어 푸시를 받지 않는다.
            - 리뷰 작성은 매일 09~22시 KST 정각 틱의 봇 작성기가 한다(`kbap.review-bot.enabled`).
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "보장 성공 — 전체 봇 목록과 이번에 만든 수"),
        ApiResponse(responseCode = "400", description = "count 누락·범위 밖(1~50)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun ensureBots(request: AdminReviewBotRequest): ResponseEntity<BaseResponse<AdminReviewBotResponse>>

    @Operation(
        summary = "기존 봇 리뷰를 짧은 말투로 재작성(KB-943)",
        description = """
            봇 회원(is_bot)의 ACTIVE 음식 리뷰 전체에서 리뷰별로 (`seed`, 리뷰 id) 가 정하는 `ratio` 몫을 표본으로 고정하고,
            그중 **언어별 앱 카드 상한(라틴·키릴 80·한중일 40·태국어 60자)을 아직 넘는 것**을 id 순 `limit` 개씩 같은 음식·같은 별점·같은 언어(리뷰 language, 없으면 봇 국가 언어, 그것도 없으면 영어)로
            새 리뷰 봇 생성기(짧은 인터넷 말투·상한 강제)를 불러 **content 만** 바꾼다(updated_at 갱신). 사진·별점·작성일·좋아요·language 는 그대로.
            - `dryRun=true`(기본): 생성까지 하되 저장하지 않는다(유료 생성 호출이 일어난다).
            - 같은 seed 로 여러 번 나눠 불러도 표본(ratio 몫)을 넘지 않는다. 같은 seed 로 응답의 `nextAfterId` 를 `afterId` 로 넘기며 null 이 될 때까지 부르고, 한 바퀴 뒤 남은 `candidates`(실패분)는 afterId 없이 다시 부른다. **실행(dryRun=false)은 seed 필수**.
            - 스타일·문장 수는 (seed, 리뷰 id, 시도)로 정해져 같은 seed 면 dryRun 과 실행이 같고, 가드에 걸린 재시도는 다른 스타일을 받는다.
            - 이미 상한 안인 리뷰는 대상에서 빠지므로 같은 리뷰를 두 번 고치지 않는다. 생성 실패·상한 초과·그사이 수정·저장 오류는 그 리뷰만 `failedIds`.
            - 원문은 응답 `sample`(앞 5건)과 INFO 로그(리뷰 id·원문)에 남는다. 저장된 번역은 원문 해시가 달라져 자연히 무효다.
            - `X-Target-Environment`(dev·prod)가 서버 환경(큐레이션 환경 값 재사용)과 다르면 409 REVIEW-006.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재작성(또는 dryRun) 결과"),
        ApiResponse(responseCode = "400", description = "ratio 0~1 밖·limit 1~40 밖·실행인데 seed 없음·생성기 꺼짐(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ApiResponse(responseCode = "409", description = "요청 환경이 서버 환경과 다름(REVIEW-006)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun rewriteReviews(target: String, request: AdminReviewBotRewriteRequest): ResponseEntity<BaseResponse<ReviewBotRewriteResult>>

    @Operation(
        summary = "편의점 가공식품 봇 리뷰 일괄 작성(KB-951)",
        description = """
            리뷰 봇이 가공식품 리뷰를 쓴다. 대상은 `ids`(가공식품 id 목록)이거나, 없으면 편의점 대표 라인(#503 seed) 상위 `topLines`(기본 전체)의 묶음 대표 상품이다.
            - 상품별 목표 수: 라인 순위 상위 10 은 8~12, 나머지(라인 밖 포함) 3~6 — `seed` 와 상품 id 로 정해 같은 seed 면 몇 번 불러도 같다.
              이미 그 묶음에 달린 봇 리뷰 수를 빼고 모자란 만큼만 쓰므로, `limit`(기본 20, 최대 40 — ALB idle timeout 130s 안) 으로 나눠 불러도 목표를 넘지 않는다.
            - 문구는 음식 봇과 같은 스타일·금지어·앱 카드 길이 규칙 + 편의점 포장 상품 문맥(식당·직원·서빙 표현은 가드가 거절). 국가·언어 분포·별점 분포도 음식 봇과 같다.
            - 맛 축(spiciness·sweetness, 1~5 또는 null)은 LLM 이 상품명·식품유형·원재료로 정한다. 저장은 가공식품 리뷰와 같은 도메인 경로(가공식품 리뷰 쓰기 스위치가 꺼져 있으면 저장되지 않는다).
            - 작성일은 최근 4주 09~21시 KST 사이로 흩는다. 사진은 상품 사진 풀(`processed:{report_no}` 키)이 있을 때만 붙고, 지금은 텍스트만이다.
            - `dryRun=true`(기본): 생성까지 하고 저장하지 않는다(유료 생성 호출). **실행(dryRun=false)은 seed 필수**.
            - `X-Target-Environment` 가 서버 환경과 다르면 409 REVIEW-006. **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "작성(또는 dryRun) 결과와 미리보기"),
        ApiResponse(responseCode = "400", description = "limit 1~40 밖·topLines 1 미만·실행인데 seed 없음·봇 없음·생성기 꺼짐(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ApiResponse(responseCode = "409", description = "요청 환경이 서버 환경과 다름(REVIEW-006)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun writeProcessedFoodReviews(target: String, request: AdminReviewBotProcessedFoodRequest): ResponseEntity<BaseResponse<ReviewBotProcessedFoodRunResult>>

    @Operation(
        summary = "봇 가공식품 리뷰 사진 중복 정리(S-935)",
        description = """
            봇 회원의 ACTIVE 가공식품 리뷰 중 같은 사진 경로를 쓰는 리뷰가 여럿이면, 작성일이 가장 이른 리뷰만 그 사진을 남기고 나머지 리뷰에서는 그 사진만 뗀다.
            본문·별점·작성일·updated_at 은 그대로이고, 사진이 다 빠지면 사진 없음이 된다. 사람 리뷰는 보지 않는다.
            - `dryRun=true`(기본): 바꿀 리뷰 수·뗄 참조 수·앞 10건 표본만 준다.
            - 실행은 리뷰 봇 분산 락을 잡는다(못 잡으면 409 REVIEW-007).
            - `X-Target-Environment` 가 서버 환경과 다르면 409 REVIEW-006. **ADMIN 역할 JWT 전용**. prod 실행은 별도 승인.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정리(또는 dryRun) 결과"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ApiResponse(responseCode = "409", description = "환경 불일치(REVIEW-006)·리뷰 봇 작업 중(REVIEW-007)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun dedupeProcessedFoodPhotos(target: String, request: AdminReviewBotPhotoDedupeRequest): ResponseEntity<BaseResponse<ReviewBotPhotoDedupeResult>>

    @Operation(
        summary = "지정 사진·작성일로 봇 가공식품 리뷰 1건 작성",
        description = """
            운영 데이터용 — 직접 찍은 사진(봇 사진 풀에 등록된 것)으로 가공식품 봇 리뷰 1건을 쓴다. 본문·맛 축은 일괄 작성과 같은 생성기가 만든다.
            - imagePaths: 1~2장, `{prefix}/images/review-bot/<key>/<n>.webp` 로 images/sync 가 활성 봇 소유로 등록한 것만. 이미 다른 리뷰(봇·사람 모두)에 붙은 사진은 거절(400 REVIEW-003).
            - createdAt: 생략하면 지금. 미래는 400 COMMON-002.
            - botId: 생략하면 그 상품 묶음에 아직 리뷰를 안 쓴 봇 중 무작위. 지정한 봇이 이미 썼거나 봇이 아니면 400 COMMON-002.
            - rating: 생략하면 생성기 규칙(1~5). 생성 실패·가드 거절이면 400 COMMON-002(payload reason=generation).
            - `dryRun=true`(기본): 생성한 본문만 주고 저장하지 않는다. 실행은 리뷰 봇 분산 락을 잡는다(못 잡으면 409 REVIEW-007).
            - `X-Target-Environment` 가 서버 환경과 다르면 409 REVIEW-006. prod 허용. **ADMIN 역할 JWT 전용**. 가공식품 리뷰 쓰기가 꺼진 환경은 403 PFOOD-005.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "작성(또는 dryRun) 결과"),
        ApiResponse(responseCode = "400", description = "사진·작성일·봇·별점 검증 실패(REVIEW-003·COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)·가공식품 리뷰 닫힘(PFOOD-005)"),
        ApiResponse(responseCode = "404", description = "상품 없음(PFOOD-001)"),
        ApiResponse(responseCode = "409", description = "환경 불일치(REVIEW-006)·리뷰 봇 작업 중(REVIEW-007)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun composeProcessedFoodReview(target: String, request: AdminReviewBotComposeRequest): ResponseEntity<BaseResponse<ReviewBotProcessedFoodComposeResult>>

    @Operation(
        summary = "봇 가공식품 리뷰 작성일 재분산(KB-966)",
        description = """
            봇 회원의 ACTIVE 가공식품 리뷰 작성일을 spreadDays 와 같은 규칙으로 다시 배정한다 — 상품별로 최근 days 일(KST, 오늘 포함) 안 하루 한 건·KST 08~23시·미래 없음, 리뷰 id 순서를 유지하며 updated_at 도 같은 값.
            사람 리뷰·음식 리뷰는 보지 않는다. 일회성(dev 정리용) — **prod 는 거절(409 REVIEW-006)**, 운영은 생성 때 spreadDays 로 한다.
            - `dryRun=true`(기본): 대상 상품·리뷰 수와 앞 10건 표본(전·후 작성일)만 준다.
            - 실행은 리뷰 봇 분산 락을 잡는다(못 잡으면 409 REVIEW-007). `X-Target-Environment` 가 서버 환경과 다르면 409 REVIEW-006. **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "재분산(또는 dryRun) 결과"),
        ApiResponse(responseCode = "400", description = "days 범위 밖(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ApiResponse(responseCode = "409", description = "환경 불일치·prod(REVIEW-006)·리뷰 봇 작업 중(REVIEW-007)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun redistributeProcessedFoodReviewDates(target: String, request: AdminReviewBotRedistributeRequest): ResponseEntity<BaseResponse<ReviewBotRedistributeResult>>

    @Operation(summary = "리뷰 봇 사진 재고 조회", description = "사용 가능·미등록·보류 사진을 조회한다. 등록행·리뷰·저장소를 변경하지 않는다.")
    @SecurityRequirement(name = "bearerAuth")
    fun getPhotoInventory(): ResponseEntity<BaseResponse<ReviewBotPhotoInventory>>

    @Operation(
        summary = "리뷰 봇 사진 등록 및 보류 보고",
        description = """
            스토리지의 {key-prefix}/images/review-bot/<key>/<n>.webp 를 활성 봇 소유로 등록한다.
            key는 sha256(korean_name)[:12] 소문자 hex다. 별도 photo-sync-enabled 설정이 켜져 있어야 등록할 수 있다.
            소실·일반 참조·소유 또는 상태 충돌은 held에 보고한다. 등록행 복구, 소유 변경, 사진 분리, 리뷰 삭제는 수행하지 않는다.
            기존 리뷰는 그대로 남아 소실 사진이 깨져 보일 수 있다. 새 리뷰에는 사용 가능한 사진만 1~3장 붙인다.
            기존 force 파라미터는 호환을 위해 받지만 삭제·복구를 허용하지 않는다. 삭제·복구 관련 카운터는 항상 0이다.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "등록 수·보류 사유 및 호환 카운터"),
        ApiResponse(responseCode = "400", description = "등록 기능이 꺼져 있거나 활성 봇 계정이 없음(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun syncPhotoPool(
        @Parameter(description = "호환용 파라미터. true여도 자동 삭제·복구하지 않는다", example = "false")
        force: Boolean,
    ): ResponseEntity<BaseResponse<AdminReviewBotPhotoSyncResponse>>
}

@Schema(description = "리뷰 봇 사진 등록 및 보류 결과")
data class AdminReviewBotPhotoSyncResponse(
    val registeredCount: Int,
    val skippedCount: Int,
    val restoredCount: Int,
    val removedCount: Int,
    val detachedReviewCount: Int,
    val deletedReviewCount: Int,
    val failedReviewCount: Int,
    val unparseableReviewIds: List<Long>,
    val detectionFailed: Boolean,
    val removalSkipped: Boolean,
    val imagesPerKey: Map<String, Int>,
    val held: List<ReviewBotPhotoHold>,
) {
    companion object {
        fun from(result: ReviewBotPhotoSyncResult): AdminReviewBotPhotoSyncResponse =
            AdminReviewBotPhotoSyncResponse(
                registeredCount = result.registeredCount,
                skippedCount = result.skippedCount,
                restoredCount = 0,
                removedCount = 0,
                detachedReviewCount = 0,
                deletedReviewCount = 0,
                failedReviewCount = 0,
                unparseableReviewIds = emptyList(),
                detectionFailed = false,
                removalSkipped = true,
                imagesPerKey = result.imagesPerKey,
                held = result.held,
            )
    }
}

@Schema(description = "봇 가공식품 리뷰 사진 중복 정리 요청")
data class AdminReviewBotPhotoDedupeRequest(
    @field:Schema(description = "true(기본)면 계획만", example = "true")
    val dryRun: Boolean = true,
)

@Schema(description = "가공식품 봇 리뷰 일괄 작성 요청")
data class AdminReviewBotProcessedFoodRequest(
    @field:Schema(description = "true(기본)면 생성만 하고 저장하지 않는다", example = "true")
    val dryRun: Boolean = true,
    @field:Min(1, message = "limit 는 1 이상이어야 합니다")
    @field:Max(40, message = "limit 는 40 이하여야 합니다")
    @field:Schema(description = "한 번에 만들 최대 리뷰 수(1~40, 기본 20)", example = "20")
    val limit: Int = 20,
    @field:Schema(description = "대상 가공식품 id 목록 — 주면 topLines 는 무시한다", nullable = true)
    val ids: List<Long>? = null,
    @field:Min(1, message = "topLines 는 1 이상이어야 합니다")
    @field:Schema(description = "편의점 대표 라인 상위 N(기본 전체) — ids 가 없을 때만", example = "10", nullable = true)
    val topLines: Int? = null,
    @field:Schema(description = "목표 수·봇·문구 스타일·작성일 seed — 실행이면 필수, 같은 값이면 같은 계획", example = "20261102", nullable = true)
    val seed: Long? = null,
    @field:Min(1, message = "spreadDays 는 1 이상이어야 합니다")
    @field:Max(90, message = "spreadDays 는 90 이하여야 합니다")
    @field:Schema(
        description = "작성일 분산 일수(1~90, KB-966) — 주면 상품별 리뷰 작성일을 최근 N일(KST, 오늘 포함) 안에 고르게 배정한다: 한 상품은 하루 한 건, KST 08~23시, 미래 없음. 생략하면 기존 규칙(최근 28일·그 상품의 마지막 리뷰 이후·KST 09~21시)",
        example = "30",
        nullable = true,
    )
    val spreadDays: Int? = null,
)

@Schema(description = "지정 사진·작성일 봇 가공식품 리뷰 1건 요청")
data class AdminReviewBotComposeRequest(
    @field:NotNull(message = "productId 는 필수입니다")
    @field:Schema(description = "가공식품 id", example = "2408", requiredMode = Schema.RequiredMode.REQUIRED)
    val productId: Long? = null,
    @field:NotNull(message = "imagePaths 는 필수입니다")
    @field:Size(min = 1, max = 2, message = "imagePaths 는 1~2장이어야 합니다")
    @field:Schema(description = "봇 사진 풀에 등록된 사진 경로 1~2장", example = "[\"prod/images/review-bot/1fef5e4f88d6/1.webp\"]", requiredMode = Schema.RequiredMode.REQUIRED)
    val imagePaths: List<String>? = null,
    @field:Schema(description = "작성일(오프셋 포함 ISO-8601) — 생략하면 지금, 미래 금지", example = "2026-11-03T21:30:00+09:00", nullable = true)
    val createdAt: OffsetDateTime? = null,
    @field:Schema(description = "봇 회원 id — 생략하면 그 상품에 아직 안 쓴 봇 무작위", nullable = true)
    val botId: Long? = null,
    @field:Schema(description = "별점 1~5 — 생략하면 생성기 규칙", example = "5", nullable = true)
    val rating: Int? = null,
    @field:Schema(description = "true(기본)면 생성만", example = "true")
    val dryRun: Boolean = true,
)

@Schema(description = "봇 가공식품 리뷰 작성일 재분산 요청(KB-966)")
data class AdminReviewBotRedistributeRequest(
    @field:Schema(description = "true(기본)면 계획만", example = "true")
    val dryRun: Boolean = true,
    @field:Min(1, message = "days 는 1 이상이어야 합니다")
    @field:Max(90, message = "days 는 90 이하여야 합니다")
    @field:Schema(description = "분산 일수(1~90) — spreadDays 와 같은 규칙", example = "30")
    val days: Int = 30,
    @field:Schema(description = "배정 seed — 같은 값이면 같은 계획", example = "20261102")
    val seed: Long = 0,
)

@Schema(description = "기존 봇 리뷰 재작성 요청")
data class AdminReviewBotRewriteRequest(
    @field:NotNull(message = "ratio 는 필수입니다")
    @field:DecimalMin(value = "0", message = "ratio 는 0 이상이어야 합니다")
    @field:DecimalMax(value = "1", message = "ratio 는 1 이하여야 합니다")
    @field:Schema(description = "대상 중 다시 쓸 비율(0~1)", example = "0.5", requiredMode = Schema.RequiredMode.REQUIRED)
    val ratio: Double? = null,
    @field:Schema(description = "true(기본)면 생성만 하고 저장하지 않는다", example = "true")
    val dryRun: Boolean = true,
    @field:Min(1, message = "limit 는 1 이상이어야 합니다")
    @field:Max(40, message = "limit 는 40 이하여야 합니다")
    @field:Schema(description = "한 번에 다시 쓸 최대 건수(1~40, 기본 20 — 순차 생성이 ALB idle timeout 130s 안에 끝나는 크기). 남은 대상은 같은 seed 로 다시 부른다", example = "20")
    val limit: Int = 20,
    @field:Schema(description = "표본 seed — 리뷰별로 (seed, 리뷰 id) 가 표본 포함을 정하므로 같은 seed 면 몇 번 나눠 불러도 같은 표본(ratio 몫)에 수렴한다. dryRun=false 면 필수", example = "20261030", nullable = true)
    val seed: Long? = null,
    @field:Schema(description = "커서 — 직전 응답의 nextAfterId. 이 id 다음 대상부터 처리한다(매번 실패하는 리뷰를 지나가기 위함). 없으면 처음부터", example = "1234", nullable = true)
    val afterId: Long? = null,
)

@Schema(description = "리뷰 봇 계정 보장 요청")
data class AdminReviewBotRequest(
    @field:NotNull(message = "count 는 필수입니다")
    @field:Min(1, message = "count 는 1 이상이어야 합니다")
    @field:Max(50, message = "count 는 50 이하여야 합니다")
    @field:Schema(description = "유지할 활성 봇 계정 수", example = "15", requiredMode = Schema.RequiredMode.REQUIRED)
    val count: Int?,
)

@Schema(description = "리뷰 봇 계정 목록")
data class AdminReviewBotResponse(
    @field:Schema(description = "이번 호출에서 새로 만든 봇 수", example = "15")
    val createdCount: Int,
    val bots: List<Bot>,
) {
    @Schema(description = "봇 계정")
    data class Bot(
        val memberId: Long,
        val nickname: String?,
        val countryCode: String?,
        val reviewCount: Int,
    )

    companion object {
        fun from(result: ReviewBotAccountsResult): AdminReviewBotResponse =
            AdminReviewBotResponse(
                createdCount = result.createdCount,
                bots = result.bots.map { Bot(it.id, it.nickname, it.countryCode, it.reviewCount) },
            )
    }
}
