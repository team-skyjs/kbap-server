package com.kbap.api.scan

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
            "환산(price ÷ krwPerUnit)과 통화별 반올림은 클라이언트가 수행한다.",
    )
    val currency: CurrencyResponse,
) {
    @Schema(description = "개별 메뉴 항목의 판정 결과")
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
    )

    @Schema(description = "기피성분 1건의 겹침 판정 — 표시명·경고 수준은 음식 상세의 성분 규칙과 동일")
    data class AvoidanceOverlapResponse(
        @field:Schema(description = "성분 코드 식별자", example = "SHRIMP")
        val code: String,

        @field:Schema(description = "성분 표시명 — 요청 lang 번역, 번역 부재 시 한국어 원문", example = "Shrimp")
        val name: String,

        @field:Schema(description = "이 메뉴의 성분 데이터에 해당 성분이 존재하는지(포함 확률 임계값 없음)", example = "true")
        val overlapped: Boolean,

        @field:Schema(
            description = "겹친 성분의 경고 수준 — 포함 확률 기반(10 미만 SAFE / 10~59 CAUTION / 60 이상 DANGER). " +
                "overlapped=false 면 null.",
            example = "DANGER",
            allowableValues = ["SAFE", "CAUTION", "DANGER"],
            nullable = true,
        )
        val riskLevel: String?,
    )

    @Schema(description = "통화 환산 정보 — 값은 참고용 고정 스냅샷이며 실시간 시세가 아니다")
    data class CurrencyResponse(
        @field:Schema(description = "회원 프로필 통화의 ISO 4217 코드", example = "USD")
        val code: String,

        @field:Schema(description = "해당 통화 1단위당 원화 금액. 클라이언트 환산식: price ÷ krwPerUnit", example = "1416.0000")
        val krwPerUnit: BigDecimal,
    )

    companion object {
        fun from(result: ScanResult, currency: CurrencyCode): ScanV2Response =
            ScanV2Response(
                degraded = result.degraded,
                currency = CurrencyResponse(code = currency.name, krwPerUnit = currency.krwPerUnit),
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
                    )
                },
            )
    }
}
