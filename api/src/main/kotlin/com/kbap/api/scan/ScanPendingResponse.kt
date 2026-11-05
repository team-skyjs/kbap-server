package com.kbap.api.scan

import com.kbap.common.port.llm.MenuNameKind
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "DB 에 없는 메뉴의 LLM 추정 결과(S-959)")
data class ScanPendingResponse(
    @field:Schema(description = "추정이 끝난 항목만, 스캔 결과 순서대로")
    val items: List<ScanPendingItemResponse>,
    @field:Schema(description = "전부 끝났거나 스캔 뒤 15초가 지나면 true — 그 뒤 도착한 추정은 캐시에만 남는다")
    val done: Boolean,
    @field:Schema(description = "모든 항목의 imageStatus 가 GENERATING 이 아니거나 스캔 뒤 60초가 지나면 true. 앱은 done && imagesDone 이면 폴링을 멈춘다(상태는 10분 보관)")
    val imagesDone: Boolean = true,
)

enum class EstimatedImageStatus { GENERATING, READY, NONE }

@Schema(description = "LLM 이 추정한 메뉴 한 항목 — riskLevel 은 CAUTION 또는 UNKNOWN 뿐(SAFE 없음)")
data class ScanPendingItemResponse(
    @field:Schema(description = "스캔 v2 응답 항목의 pendingId")
    val pendingId: String,
    @field:Schema(description = "DISH(음식)·DRINK(술·음료)·NOT_FOOD(가게 이름·구역 제목 등 — 앱은 숨긴다)")
    val kind: MenuNameKind,
    @field:Schema(description = "영어 이름 — NOT_FOOD 면 한국어 원문", example = "Beef short rib soup")
    val name: String,
    @field:Schema(description = "메뉴판에서 읽은 한국어 이름", example = "갈비탕")
    val koreanName: String,
    @field:Schema(description = "영어 한 줄 설명 — NOT_FOOD 면 null", nullable = true)
    val description: String?,
    @field:Schema(description = "CAUTION(추정 재료 또는 그 하위 재료가 회원 회피 재료와 겹침) 또는 UNKNOWN(판정 불가). LLM 추정이라 SAFE 는 타입상 내려갈 수 없다", example = "UNKNOWN")
    val riskLevel: EstimatedRisk,
    @field:Schema(description = "추정 주재료 코드(IngredientCode) 중 회원 회피 재료와 겹치는 것만(riskLevel 과 같은 판정 — 추정 코드나 그 하위가 회원 회피 closure 에 들면 포함). 겹치지 않거나 회피 재료가 없는 회원·게스트는 빈 배열. 비어 있지 않으면 riskLevel=CAUTION", example = "[\"BEEF\"]")
    val avoidances: List<String>,
    @field:Schema(description = "매운 정도 0~10, 판단 불가 -1", example = "1")
    val spiciness: Int,
    @field:Schema(description = "추정 확신도 0~1", nullable = true)
    val confidence: Double?,
    @field:Schema(description = "LLM 추정 항목 표시 — 항상 true")
    val aiEstimated: Boolean = true,
    @field:Schema(description = "이 이름의 음식 행이 READY 가 아닌 채로 있으면 true(어드민이 검수로 보낼 수 있음)")
    val existsNotReady: Boolean,
    @field:Schema(description = "existsNotReady 일 때 그 음식 행 id", nullable = true)
    val foodId: Long?,
    @field:Schema(description = "15초 마감까지 추정이 없어 서버가 kind=DISH·UNKNOWN 으로 채운 항목이면 true, LLM 추정 결과면 false")
    val filled: Boolean = false,
    @field:Schema(description = "메뉴판에서 읽은 가격(원) — LLM 이 만들지 않는다. 없으면 null", nullable = true, example = "11000")
    val priceKrw: Int? = null,
    @field:Schema(description = "추정 주재료 코드(IngredientCode) 전체 — 회원 회피와 겹치는 것은 avoidances 에도 있다", example = "[\"BEEF\",\"WHEAT\"]")
    val estimatedCodes: List<String> = emptyList(),
    @field:Schema(description = "사진 상태 — GENERATING(만드는 중, 같은 항목이 폴링 중 READY/NONE 으로 바뀐다)·READY·NONE(없음·일 상한·실패·NOT_FOOD)")
    val imageStatus: EstimatedImageStatus = EstimatedImageStatus.NONE,
    @field:Schema(description = "READY 일 때 사진 URL", nullable = true)
    val imageUrl: String? = null,
    @field:Schema(description = "즉석 생성한 AI 사진이면 true — 검수된 정식 음식 사진이면 false")
    val aiGeneratedImage: Boolean = false,
)
