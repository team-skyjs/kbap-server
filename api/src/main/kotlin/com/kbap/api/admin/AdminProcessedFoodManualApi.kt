package com.kbap.api.admin

import com.kbap.api.core.BaseResponse
import com.kbap.api.processedfood.ProcessedFoodDetailResponse
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity

@Tag(name = "Admin · 가공식품 수동 등록", description = "HACCP 미러에 없는 편의점 상품을 실물 표시 사항으로 등록·정정한다(KB-952)")
interface AdminProcessedFoodManualApi {
    @Operation(
        summary = "가공식품 수동 등록",
        description = """
            HACCP 미러에 없는 상품을 실물 앞·뒷면 표시 사항으로 등록한다. source=MANUAL, 품목보고번호 자리에는 수동 키 `M` + 6자리 순번(M000001 …)을 매긴다.
            - 수동 키는 영문 접두라 숫자뿐인 HACCP·C002 품목보고번호와 겹치지 않는다. 같은 번호가 생기더라도 HACCP 미러는 HACCP·C002 가 아닌 행을 덮지 않고 건너뛴다.
            - 정규화 이름·내용 해시 등 파생 값은 미러 적재와 같은 함수로 만든다. 판정(원재료 → 회피 코드)·번역·누끼 업로드·검수·묶음 키는 HACCP 행과 똑같이 동작한다.
            - MANUAL 행은 미러 last_seen 기준 단종 판정 대상이 아니다.
            - 응답은 가공식품 상세(비회원 기준) — 바로 판정·표시 정보를 확인할 수 있다. **ADMIN 역할 JWT 전용**.
        """,
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록 성공 — 상세"),
        ApiResponse(responseCode = "400", description = "상품명·제조사 누락, 길이 초과(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun createManualProduct(request: AdminProcessedFoodManualRequest): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>>

    @Operation(
        summary = "가공식품 수동 등록 정정",
        description = "오타 정정용 — 요청 값으로 표시 사항 전체를 바꾼다(품목보고번호·출처는 그대로). MANUAL 행만 고칠 수 있다(HACCP·C002 행은 409 PFOOD-011). 삭제는 제공하지 않는다. **ADMIN 역할 JWT 전용**.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "정정 성공 — 상세"),
        ApiResponse(responseCode = "400", description = "상품명·제조사 누락, 길이 초과(COMMON-002)"),
        ApiResponse(responseCode = "403", description = "ADMIN 역할이 아닌 토큰(AUTH-008)"),
        ApiResponse(responseCode = "404", description = "없는 가공식품(PFOOD-001 계열)"),
        ApiResponse(responseCode = "409", description = "수동 등록 행이 아님(PFOOD-011)"),
    )
    @SecurityRequirement(name = "bearerAuth")
    fun updateManualProduct(id: Long, request: AdminProcessedFoodManualRequest): ResponseEntity<BaseResponse<ProcessedFoodDetailResponse>>
}

@Schema(description = "가공식품 수동 등록·정정 요청 — 포장 표시 그대로")
data class AdminProcessedFoodManualRequest(
    @field:NotBlank(message = "productName 은 필수입니다")
    @field:Size(max = ProcessedFoodProduct.NAME_LENGTH, message = "productName 이 너무 깁니다")
    @field:Schema(description = "제품명(포장 표시)", example = "와사비맛 아몬드", requiredMode = Schema.RequiredMode.REQUIRED)
    val productName: String? = null,
    @field:NotBlank(message = "manufacturer 는 필수입니다")
    @field:Size(max = ProcessedFoodProduct.MAKER_LENGTH, message = "manufacturer 가 너무 깁니다")
    @field:Schema(description = "제조사(또는 판매원)", example = "㈜바프", requiredMode = Schema.RequiredMode.REQUIRED)
    val manufacturer: String? = null,
    @field:Size(max = ProcessedFoodProduct.FOOD_TYPE_LENGTH, message = "foodType 이 너무 깁니다")
    @field:Schema(description = "식품유형", example = "땅콩 또는 견과류가공품", nullable = true)
    val foodType: String? = null,
    @field:Size(max = ProcessedFoodProduct.CAPACITY_LENGTH, message = "capacity 가 너무 깁니다")
    @field:Schema(description = "용량", example = "30g", nullable = true)
    val capacity: String? = null,
    @field:Schema(description = "원재료명(표시 그대로)", example = "아몬드(미국산), 와사비시즈닝, 설탕", nullable = true)
    val rawMaterials: String? = null,
    @field:Size(max = ProcessedFoodProduct.ALLERGENS_LENGTH, message = "allergens 가 너무 깁니다")
    @field:Schema(description = "알레르기 표시(혼입 문구 포함 그대로)", example = "아몬드, 대두, 밀 함유", nullable = true)
    val allergens: String? = null,
    @field:Schema(description = "영양 정보 문구(선택)", nullable = true)
    val nutrients: String? = null,
)
