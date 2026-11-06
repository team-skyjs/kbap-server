package com.kbap.api.scan

import com.fasterxml.jackson.annotation.JsonInclude
import com.kbap.api.food.LinkedProcessedFoodResponse
import com.kbap.common.domain.CurrencyCode
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

@Schema(description = "메뉴 스캔 v2 판정 결과 — 사진에서 서버가 추출한 메뉴별 매칭·위험도")
data class ScanV2Response(
    @field:Schema(
        description = "정제 서비스 미적용 여부. true 면 LLM 이 없거나 실패해 음식 여부를 판정하지 못한 상태다.",
        example = "false",
    )
    val degraded: Boolean,

    @field:Schema(
        description = "사진에서 추출된 메뉴의 판정 결과. 순서는 추출 순서이며 클라이언트 항목과의 매칭 키(idx)는 없다.",
    )
    val results: List<ItemRiskResponse>,

    @field:Schema(
        description = "요청 currency 파라미터 기준 통화 환산 정보 — 회원 프로필 통화 설정을 읽지 않는다. " +
            "환산(price ÷ krwPerUnit)과 통화별 반올림은 클라이언트가 수행한다. " +
            "환율 제공처 조회에 실패한 요청은 이 필드만 null 이고 스캔 결과는 정상이다.",
        nullable = true,
    )
    val currency: CurrencyResponse?,

    @field:Schema(
        description = "DB 에 없는 메뉴의 LLM 추정을 받을 스캔 식별자(S-959) — pendingId 가 붙은 항목이 하나라도 있을 때만. " +
            "GET /api/scans/{scanId}/pending 을 1초 간격으로 최대 15초 폴링한다.",
        nullable = true,
    )
    val scanId: String? = null,
) {
    @Schema(name = "ScanV2ItemRiskResponse", description = "개별 메뉴 항목의 판정 결과")
    data class ItemRiskResponse(
        @field:Schema(
            description = "조회 가능한(완성된) 음식과 매칭됐는지. false 면 조사 대기라 위험도를 알 수 없다(riskLevel=UNKNOWN).",
            example = "true",
        )
        val matched: Boolean,

        @field:Schema(
            description = "음식 식별자(PK). matched=false 여도 조사 대기로 등록된 음식이면 값이 있고, 판정 자체가 불가하면 null",
            example = "7",
            nullable = true,
        )
        val foodId: Long?,

        @field:Schema(
            description = "위험도. matched=true 는 사용자 회피 성분 기준 판정값, matched=false 는 항상 UNKNOWN",
            example = "SAFE",
            allowableValues = ["SAFE", "CAUTION", "DANGER", "UNKNOWN"],
        )
        val riskLevel: String,

        @field:Schema(
            description = "표시용 메뉴명. matched=true 면 음식의 요청 lang 번역명(번역 부재 시 한국어명), " +
                "matched=false 면 비전이 정제한 표준 한국어명.",
            example = "Kimchi Stew",
            nullable = true,
        )
        val name: String?,

        @field:Schema(
            description = "언어와 무관한 한국어 메뉴명(원본 표기 보존).",
            example = "김치 찌개",
            nullable = true,
        )
        val koreanName: String?,

        @field:Schema(
            description = "메뉴판에 표기된 가격(원 단위 정수). 표기가 없으면 null.",
            example = "9000",
            nullable = true,
        )
        val price: Int?,

        @field:Schema(
            description = "음식 사진 URL. matched=true 면 음식 대표 이미지, 비매칭이거나 대표 이미지가 없으면 " +
                "디폴트 음식 이미지 경로가 내려간다 — 항상 값이 있다.",
            example = "https://cdn.example.com/images/webp/kimchi-stew.webp",
        )
        val imageRef: String,

        @field:Schema(
            description = "요청 회원의 기피성분 전체와 이 메뉴와의 겹침 여부. 온보딩 미완료(프로필 없음) 회원은 null. " +
                "프로필은 있으나 기피 미등록이면 빈 배열. matched=false(degraded 포함)면 항상 빈 배열 — 겹침 판정 불가" +
                "(riskLevel UNKNOWN 과 일관). 순서는 성분 카탈로그 선언 순서로 고정.",
            nullable = true,
        )
        val avoidances: List<AvoidanceOverlapResponse>?,

        @field:Schema(
            description = "유사 완성 음식으로 대체된 항목인지. X-API-Version 3.0 이상에서만 true/false, 2.0 은 항상 null.",
            nullable = true,
        )
        val substituted: Boolean?,

        @field:Schema(
            description = "대체 전 비전이 정제한 표준 한국어 메뉴명. 3.0 이상이고 substituted=true 일 때만 값, 그 외 null.",
            example = "김치찌게",
            nullable = true,
        )
        val originalScannedName: String?,
        @field:Schema(
            description = "LLM 추정 대기 항목 식별자(S-959) — matched=false 이고 대체되지 않은 항목만. pending 응답의 pendingId 와 짝",
            nullable = true,
        )
        val pendingId: String? = null,
        @field:Schema(
            description = "매운 정도 0~10, 미평가 -1 — 매칭된 음식 값. 매칭 안 된 항목은 null(AI 추정 항목은 pending 응답의 spiciness)",
            nullable = true,
        )
        val spiciness: Int? = null,
        @field:Schema(
            description = "같은 메뉴의 크기·수량별 가격(메뉴판에서 읽은 값) — 2개 이상일 때만, 아니면 null. price 는 첫 옵션 값(구 앱 호환)",
            nullable = true,
        )
        val priceOptions: List<PriceOptionResponse>? = null,
        @field:Schema(description = "메뉴가 아닌 주문 옵션·단일 재료어(추가·사리·소스·공기밥 등)면 true — 판정·추정·상세 없음, 앱은 원문만 보여 준다")
        val notMenu: Boolean = false,
        @field:JsonInclude(JsonInclude.Include.NON_NULL)
        @field:Schema(description = "matched 음식에 연결된 가공식품 대표 상품(S-968, 콜라·사이다 같은 음료) — 연결이 없으면 필드가 빠진다. 앱은 imageUrl 을 imageRef 보다 먼저 보여 준다. riskLevel·avoidances 는 음식 값 그대로다", nullable = true)
        val linkedProcessedFood: LinkedProcessedFoodResponse? = null,
    )

    @Schema(description = "가격 옵션 한 개")
    data class PriceOptionResponse(
        @field:Schema(description = "메뉴판의 옵션 표기 그대로(예: 20개·대) — 표기가 없으면 null", nullable = true, example = "20개")
        val label: String?,
        @field:Schema(description = "원(KRW) — 읽지 못하면 null", nullable = true, example = "22000")
        val priceKrw: Int?,
    )

    @Schema(description = "기피성분 1건의 겹침 판정 — 표시명·경고 수준은 음식 상세의 성분 규칙과 동일")
    data class AvoidanceOverlapResponse(
        @field:Schema(description = "성분 코드 식별자", example = "SHRIMP")
        val code: String,

        @field:Schema(description = "성분 표시명 — 요청 lang 번역, 번역 부재 시 한국어 원문", example = "Shrimp")
        val name: String,

        @field:Schema(description = "이 메뉴의 성분 데이터에 해당 성분 또는 그 성분이 함의하는 파생 성분이 존재하는지(포함 확률 임계값 없음). " +
                "함의는 일반→구체·원재료→파생품 단방향이다(예: SHRIMP 회피면 새우젓 SALTED_SHRIMP 도 겹침)", example = "true")
        val overlapped: Boolean,

        @field:Schema(
            description = "겹친 성분의 경고 수준 — 포함 확률 기반(10 미만 SAFE / 10~59 CAUTION / 60 이상 DANGER). " +
                "함의로 여러 성분이 겹치면 가장 높은 수준. overlapped=false 면 null.",
            example = "DANGER",
            allowableValues = ["SAFE", "CAUTION", "DANGER"],
            nullable = true,
        )
        val riskLevel: String?,
    )

    @Schema(description = "통화 환산 정보 — 환율 제공처의 최근 고시값(일 1회 갱신 수준)이며 실시간 시세가 아니다")
    data class CurrencyResponse(
        @field:Schema(description = "요청 currency 파라미터의 ISO 4217 코드", example = "USD")
        val code: String,

        @field:Schema(description = "해당 통화 1단위당 원화 금액(소수 4자리). 클라이언트 환산식: price ÷ krwPerUnit", example = "1410.0726")
        val krwPerUnit: BigDecimal,
    )

    companion object {
        fun from(
            result: ScanResult,
            currency: CurrencyCode,
            krwPerUnit: BigDecimal?,
            exposeSubstitution: Boolean,
            linkedProcessedFoods: Map<Long, LinkedProcessedFoodResponse> = emptyMap(),
        ): ScanV2Response =
            ScanV2Response(
                degraded = result.degraded,
                scanId = result.scanId,
                currency = krwPerUnit?.let { CurrencyResponse(code = currency.name, krwPerUnit = it) },
                results = result.items.map {
                    ItemRiskResponse(
                        matched = it.matched,
                        foodId = it.foodId,
                        riskLevel = it.riskLevel,
                        name = it.name,
                        koreanName = it.koreanName,
                        price = it.price,
                        imageRef = it.imageRef,
                        avoidances = it.avoidances?.map { avoidance ->
                            AvoidanceOverlapResponse(
                                code = avoidance.code,
                                name = avoidance.name,
                                overlapped = avoidance.overlapped,
                                riskLevel = avoidance.riskLevel,
                            )
                        },
                        substituted = it.substituted.takeIf { exposeSubstitution },
                        originalScannedName = it.originalScannedName.takeIf { exposeSubstitution },
                        pendingId = it.pendingId,
                        spiciness = it.spiciness,
                        priceOptions = it.priceOptions?.map { option -> PriceOptionResponse(option.label, option.priceKrw) },
                        notMenu = it.notMenu,
                        linkedProcessedFood = if (it.matched) it.foodId?.let(linkedProcessedFoods::get) else null,
                    )
                },
            )
    }
}
