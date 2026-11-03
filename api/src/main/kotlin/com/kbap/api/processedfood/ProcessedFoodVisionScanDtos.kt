package com.kbap.api.processedfood

import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

@Schema(description = "가공식품 사진 스캔 요청 — 서버 비전이 사진 종류를 판별하고 앞면·성분표를 읽는다")
data class ProcessedFoodVisionScanRequest(
    @field:NotBlank(message = "imagePath 는 필수입니다")
    @field:Size(max = 512, message = "imagePath 는 512자 이하여야 합니다")
    @field:Pattern(regexp = "^(?!https?://).*", message = "imagePath 는 전체 URL 이 아닌 오브젝트 경로여야 합니다")
    @field:Schema(description = "본인이 PACKAGED_SCAN 목적으로 올린 사진 경로(2MB 이하, 긴 변 1600px 권장) — 아니면 400 SCAN-001, 2MB 초과 400 PFOOD-014", example = "images/packaged-scans/2026/11/12_550e8400-e29b-41d4-a716-446655440000.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
    val imagePath: String? = null,
    @field:NotBlank(message = "clientRequestId 는 필수입니다")
    @field:Schema(description = "앱이 만든 UUID 멱등 키 — 응답의 analysisId 가 된다. 같은 키·같은 imagePath 재전송은 같은 결과(비전 재호출·상한 차감 없음), 같은 키·다른 imagePath 는 409 SCAN-005. 형식 오류 400 COMMON-002", example = "0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10", requiredMode = Schema.RequiredMode.REQUIRED)
    val clientRequestId: String? = null,
    @field:Schema(description = "앱 언어 코드(후보 이름·라벨 표시). 미지원·생략은 en", example = "ko")
    val lang: String? = null,
)

@Schema(description = "가공식품 사진 스캔 결과")
data class ProcessedFoodVisionScanResponse(
    @field:Schema(description = "분석 ID(= clientRequestId) — PACKAGE_FRONT 는 scan/confirm, NO_CANDIDATE·LABEL_RESULT 는 scan/label 이어가기에 그대로 쓴다")
    val analysisId: String,
    @field:Schema(description = "사진 종류 — MENU 면 같은 imagePath 로 메뉴판 스캔 v2(/api/scans, 티켓)를 부른다. OTHER 면 reason 으로 안내 문구를 고른다")
    val kind: ProcessedFoodPhotoKind,
    @field:Schema(description = "종류 판별 확신도 0~1", example = "0.92")
    val confidence: Double,
    @field:Schema(description = "판단 근거(선택) — BLURRY 흐림 · TOO_DARK 어두움 · GLARE 반사 · CROPPED 잘림 · NOT_FOOD 음식 아님. 근거가 없거나 모르는 값이면 null", nullable = true)
    val reason: ProcessedFoodPhotoReason?,
    @field:Schema(description = "PACKAGE_FRONT: CANDIDATES·NO_CANDIDATE · INGREDIENT_LABEL: LABEL_RESULT · MENU·OTHER: NOT_PACKAGED")
    val state: ProcessedFoodNameScanState,
    @field:Schema(description = "PACKAGE_FRONT 일 때만", nullable = true)
    val front: ProcessedFoodVisionFrontResponse?,
    @field:Schema(description = "INGREDIENT_LABEL 일 때만 — scan/label 응답과 같은 형태(판정 규칙 동일)", nullable = true)
    val label: ProcessedFoodLabelScanResponse?,
    @field:Schema(description = "비전이 남긴 주의(예: 일부 글자 가림) — 표시용 참고, 없으면 빈 배열")
    val warnings: List<String>,
    @field:Schema(description = "가공식품 스캔은 무료 스캔 횟수에서 차감하지 않는다 — 항상 false")
    val charged: Boolean = false,
)

@Schema(description = "앞면 판독 결과")
data class ProcessedFoodVisionFrontResponse(
    @field:Schema(description = "읽은 제품명", example = "바나나맛우유", nullable = true)
    val productName: String?,
    @field:Schema(description = "읽은 제조사", example = "빙그레", nullable = true)
    val maker: String?,
    @field:Schema(description = "읽은 품목보고번호(숫자만) — 보이지 않으면 null", example = "19760344001", nullable = true)
    val reportNo: String?,
    @field:Schema(description = "후보 최대 5 — 읽은 품목보고번호가 등록돼 있으면 그 제품이 맨 위, 나머지는 번역 이름을 포함한 이름 검색. 검색 결과 항목과 같은 형태")
    val candidates: List<ProcessedFoodSummaryResponse>,
)
