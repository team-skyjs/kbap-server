package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.core.config.ApiErrors
import com.kbap.common.core.error.ErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 음식 출처 스캔", description = "ADMIN 전용(S-967, KB-1003) — 검수 화면에서 이 음식이 나온 유저 스캔 사진을 본다")
@SecurityRequirement(name = "bearerAuth")
interface AdminFoodSourceScanApi {
    @Operation(
        summary = "음식의 출처 스캔(최근 순)",
        description = """
            이 음식이 나온 스캔 결과 기록(scan_result_log)을 최근 순으로 limit(1~20, 기본 5)건. 사진 URL·스캔 종류·시각·읽힌 원문 이름·회원 id·업로드 이미지 id.
            미등록 메뉴도 스캔이 만든 미완성 음식 id 로 연결된다. 스캔 결과 기록은 이 환경에 기록이 시작된 뒤(sourceRecordedSince)부터만 있다 —
            items 가 비고 foodCreatedAt 이 그보다 이르면 '출처 기록 없음(기록 시작 전)'으로 보여 준다. linkSource=NAME_BACKFILL 은 지난 기록을 이름 정규화 일치로 사후 연결한 것.
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.FOOD_NOT_FOUND)
    fun getSourceScans(@Parameter(description = "음식 id") foodId: Long, @Parameter(description = "1~20, 기본 5") limit: Int): ResponseEntity<BaseResponse<AdminFoodSourceScans>>

    @Operation(
        summary = "지난 스캔 기록의 출처 연결 백필(일회성)",
        description = """
            연결 행 기록이 생기기 전의 메뉴 스캔 기록을 id 순으로 batchSize(1~500, 기본 200)건씩 읽어, 요약의 음식 id(매칭) 또는 이름 정규화(matchKey) 일치 음식으로 연결 행을 넣는다.
            가산 INSERT 만, 이미 있는 연결은 건너뛴다(멱등). 응답의 nextAfterLogId 가 있으면 그 값으로 다시 부른다. X-Target-Environment 필수(다르면 409 FOOD-026).
        """,
    )
    @ApiErrors(ErrorCode.INVALID_REQUEST, ErrorCode.FOOD_ENVIRONMENT_MISMATCH)
    fun backfill(@Parameter(description = "dev 또는 prod") target: String, @Parameter(description = "이 id 다음부터") afterLogId: Long, @Parameter(description = "1~500, 기본 200") batchSize: Int): ResponseEntity<BaseResponse<AdminSourceScanBackfillResult>>
}
