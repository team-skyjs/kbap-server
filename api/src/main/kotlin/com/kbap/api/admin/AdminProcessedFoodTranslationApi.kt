package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.processedfood.ProcessedFoodNameRegeneration
import com.kbap.api.processedfood.ProcessedFoodPin
import com.kbap.api.processedfood.ProcessedFoodPinItem
import com.kbap.api.processedfood.ProcessedFoodPinKind
import com.kbap.api.processedfood.ProcessedFoodPinResult
import com.kbap.api.processedfood.ProcessedFoodTranslationService
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "관리자 가공식품 번역", description = "관리자 전용 — 대표 상품 미리 번역")
@SecurityRequirement(name = "bearerAuth")
interface AdminProcessedFoodTranslationApi {
    @Operation(
        summary = "대표 상품 미리 번역 시작",
        description = """
            대표 상품 카탈로그(약 100개)의 제품명·제조사(브랜드 사전에 없을 때)·원재료·알레르기 표시를 언어마다 번역해 저장한다.
            뒤에서 돌고 바로 202 를 준다 — 진행·결과는 서버 로그('가공식품 대표 상품 미리 번역 완료'). 이미 저장된 번역은 다시 부르지 않는다.
            일일 상한(kbap.processed-food.translation.daily-budget-krw)에 닿으면 남은 항목은 건너뛴다.
            - **ADMIN 역할 JWT 전용**.
        """,
    )
    fun prewarm(
        @Parameter(description = "언어 코드 목록(쉼표) — 없으면 ko 를 뺀 9개 전부", example = "en,ja") languages: String?,
    ): ResponseEntity<BaseResponse<AdminProcessedFoodPrewarmResponse>>

    @Operation(
        summary = "가공식품 이름 번역 재생성(KB-953)",
        description = """
            저장된 PF_NAME 번역을 새 이름 규칙(브랜드는 뜻을 옮기지 않고 공식 라틴 표기 → 없으면 로마자, 설명어만 번역)으로 다시 번역해 덮어쓴다.
            캐시 버전(pf1)은 올리지 않으므로 이 API 를 부른 환경만 바뀐다(prod 는 별도 승인 뒤 같은 API).
            - `dryRun=true`(기본): 건수와 전체 비용 추정만 준다(번역 호출 없음).
            - 실행: id 순 `limit`(1~40, 기본 20)행씩 다시 번역한다. 응답 `nextAfterId` 를 다음 요청 `afterId` 로 넘기며 null 이 될 때까지 부른다. 일일 번역 상한 안에서만 돈다.
            - 실패·가드 거절 행은 빈 번역으로 저장돼 원문(한국어)으로 보이고 다음 날 다시 번역된다.
            - `X-Target-Environment`(dev·prod)가 서버 환경과 다르면 409 PFOOD-012. **ADMIN 역할 JWT 전용**.
        """,
    )
    fun regenerateNames(target: String, request: AdminProcessedFoodNameRegenerateRequest): ResponseEntity<BaseResponse<ProcessedFoodNameRegeneration>>

    @Operation(
        summary = "가공식품 이름·제조사 번역 고정(KB-960 후속)",
        description = """
            사람이 정한 언어별 표시값을 그대로 저장해 고정한다 — 가드·번역 엔진을 거치지 않는다. kind=NAME(기본)이면 제품명, MAKER 면 제조사.
            - 고정값은 상세·목록 표시에서 자동 번역·브랜드 사전보다 먼저 쓰이고, 이름 재생성(names/regenerate)·미리 번역(prewarm)·원문 해시 변경이 덮지 않는다(별도 번역 종류 PF_NAME_PINNED·PF_MAKER_PINNED 로 저장).
            - 원문(product_name)이 바뀌어도 고정은 유지한다. 해제 API 는 아직 없다.
            - 같은 이름 다른 공장(묶음 형제)은 productId 별로 따로 보낸다.
            - items 는 요청당 500건 이하(넘으면 400). 한 요청은 한 트랜잭션이고, 응답은 건수와 거절 사유만 준다(적용 항목은 되돌려주지 않음) — 큰 묶음은 500건씩 순차 호출.
            - `dryRun=true`(기본): 저장 없이 적용·무변·거절 수만.
            - `X-Target-Environment`(dev·prod)가 서버 환경과 다르면 409 PFOOD-012. **ADMIN 역할 JWT 전용**.
        """,
    )
    fun pinTranslations(target: String, request: AdminProcessedFoodPinRequest): ResponseEntity<BaseResponse<ProcessedFoodPinResult>>

    @Operation(summary = "가공식품 번역 고정 목록(검수용)", description = "한 상품의 고정된 이름·제조사 번역을 kind·언어순으로 준다. **ADMIN 역할 JWT 전용**.")
    fun getPinnedTranslations(@Parameter(description = "가공식품 id", example = "4821") productId: Long): ResponseEntity<BaseResponse<List<ProcessedFoodPin>>>
}

@Schema(description = "가공식품 이름 번역 재생성 요청")
data class AdminProcessedFoodNameRegenerateRequest(
    @field:Schema(description = "true(기본)면 건수·비용 추정만", example = "true")
    val dryRun: Boolean = true,
    @field:Min(1, message = "limit 는 1 이상이어야 합니다")
    @field:Max(40, message = "limit 는 40 이하여야 합니다")
    @field:Schema(description = "한 번에 다시 번역할 행 수(1~40, 기본 20)", example = "20")
    val limit: Int = 20,
    @field:Schema(description = "커서 — 직전 응답의 nextAfterId", nullable = true)
    val afterId: Long? = null,
)

@Schema(description = "가공식품 번역 고정 요청")
data class AdminProcessedFoodPinRequest(
    @field:Schema(description = "true(기본)면 저장하지 않는다", example = "true")
    val dryRun: Boolean = true,
    @field:Schema(description = "NAME(기본): 제품명 · MAKER: 제조사", example = "NAME")
    val kind: ProcessedFoodPinKind = ProcessedFoodPinKind.NAME,
    @field:Size(max = ProcessedFoodTranslationService.MAX_PINS_PER_REQUEST, message = "items 는 500건 이하여야 합니다")
    @field:Valid
    val items: List<ProcessedFoodPinItem> = emptyList(),
)
