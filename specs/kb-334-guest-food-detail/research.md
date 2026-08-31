# Research: 비회원 음식 상세 조회 응답 개편 (KB-334)

Technical Context 에 NEEDS CLARIFICATION 없음 — 스펙 단계에서 해소된 결정과 코드 조사 결과만 기록한다.

## Decision 1: 비회원 위험도는 필드 유지 + null (필드 제거 아님)

- **Decision**: `overallRiskStatus` 필드를 응답에서 빼지 않고 null 로 내린다.
- **Rationale**: 클라이언트가 "이 응답이 비회원 조회"임을 판별하는 단일 기준으로 쓰기로 합의(스펙 확정). 필드 제거보다 스키마 안정적.
- **Alternatives considered**: 필드 제거(스키마 요동·클라이언트 옵셔널 처리 동일), `"UNKNOWN"` 재사용(회원의 콘텐츠 미완성 UNKNOWN 과 의미 충돌 — 기각).

## Decision 2: 비회원 분기의 소유 계층 = api 조립 계층

- **Decision**: `Food.overallRisk`(도메인)·`ReviewService.getFoodRatingSummary` 시그니처는 손대지 않고, `FoodService.getDetail`(memberId null → 위험도 null)과 `FoodController.reviewSummaryOf`(memberId null → sameCountry null)가 분기를 소유한다.
- **Rationale**: "비회원 응답을 어떻게 보여줄까"는 도메인 정책이 아니라 API 계약 조립 문제. 헌법 II — API 전용 조합은 `com.kbap.api.<feature>` 소유.
- **Alternatives considered**: `overallRisk(avoidedCodes)` 에 nullable 분기 추가 — 도메인 메서드가 조회자 인증 상태를 알게 되어 기각.

## Decision 3: 국적 없는 회원과 비회원의 sameCountry 구분

- **Decision**: 비회원 → `sameCountry: null`, 국적 없는 회원 → 기존 `{0.0, 0}` 유지. 구분 키는 memberId 유무.
- **Rationale**: 스펙 SC-004(회원 응답 blur 제거 외 불변). null 은 비회원 전용 시그널로 남긴다.
- **Alternatives considered**: 국적 없는 회원도 null — 회원 계약 변경이라 범위 밖(기각, 스펙 Assumptions 기록).

## Decision 4: 계약 적용 방식 — 무버전 매핑 즉시 변경

- **Decision**: 새 X-API-Version 을 만들지 않고 기존 무버전 매핑의 응답을 바꾼다.
- **Rationale**: 클라이언트 요청으로 조율된 변경. blur 제거·nullable 화는 관대한 파싱에서 안전. (스펙 명확화에서 확정)
- **Alternatives considered**: 버전 매핑 공존 — 컨트롤러·응답 타입 이원화 비용 대비 보호 실익 없음.

## 코드 조사 결과 (현재 동작)

- `FoodController.reviewSummaryOf`: `memberId?.let { memberService.getMemberOrNull(it) } ?: return blurred()` — 비회원·탈퇴 회원 모두 blurred. 개편 후 비회원은 실수치 경로로 합류. **탈퇴/무효 회원(memberId 있으나 조회 실패)도 비회원과 동일 취급**(sameCountry null) — 기존에도 blurred 로 동일 취급했으므로 일관.
- `ReviewService.getFoodRatingSummary(foodId, viewerCountryCode: String?)`: viewerCountryCode null 이면 sameCountry 집계를 스킵하고 `RatingSummary.sameCountry* = null/0` — 비회원 재사용에 그대로 적합.
- `FoodService.getDetail`: `avoidedCodeNames(null) = emptySet` → `overallRisk(emptySet)` → `RiskLevel.aggregate(empty) = SAFE` — SAFE 오표시의 근원. memberId null 분기가 정확한 수선 지점.
- `blur`·`blurred()` 참조처: `FoodDetailResponse`(선언)·`FoodController`(호출)·`FoodDetailReviewSectionTest`(단언) — 전수 3곳.
