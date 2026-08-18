# Feature Specification: 비회원 음식 상세 조회 응답 개편

**Feature Branch**: `kb-334-guest-food-detail`

**Created**: 2026-08-14

**Status**: Draft

**Input**: User description: "비회원(게스트)이 GET /api/foods/{foodId} 를 조회할 때: (1) overallRiskStatus 를 SAFE 대신 null 로 내려 '판별하지 않음'을 명시하고 클라이언트가 비회원 조회임을 판단하는 기준으로 삼는다, (2) bookmarked 는 현행대로 false 유지, (3) 리뷰 요약은 가리지 않는다 — overall 은 실제 수치를 그대로 주고, sameCountry 는 null 로 내린다, blur 필드는 응답에서 제거한다. 회원 조회 응답은 기존과 동일하게 유지(단 blur 필드 제거는 전 응답 공통)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 비회원도 음식 상세에서 리뷰 평판을 본다 (Priority: P1)

앱을 설치만 하고 로그인하지 않은 사용자가 음식 상세 화면을 열면, 지금은 리뷰 수치가 전부 0 으로 가려져(blur) 음식의 평판을 전혀 알 수 없다. 개편 후에는 전체 사용자의 평균 별점·리뷰 수를 회원과 동일하게 볼 수 있다. 같은 국적 리뷰 요약은 비회원에게 국적이 없으므로 "제공하지 않음(null)"으로 내려간다.

**Why this priority**: 이 기능의 발단이 된 클라이언트 요청의 핵심 — 비회원 온보딩 전 단계에서 콘텐츠 가치를 보여주는 것이 목적이다.

**Independent Test**: 인증 없이 음식 상세를 조회해 리뷰 수치가 실제 집계값으로 내려오는지 확인하는 것만으로 검증 가능.

**Acceptance Scenarios**:

1. **Given** 리뷰 3건(평균 3.7)이 있는 음식, **When** 비회원이 상세를 조회하면, **Then** `review.overall` 은 `{averageRating: 3.7, reviewCount: 3}` 이고 `review.sameCountry` 는 `null` 이다.
2. **Given** 리뷰가 없는 음식, **When** 비회원이 상세를 조회하면, **Then** `review.overall` 은 `{averageRating: 0.0, reviewCount: 0}` 이다 (리뷰 없음과 비회원 가림을 더 이상 구분할 필요가 없다).
3. **Given** 국적을 보유한 회원, **When** 상세를 조회하면, **Then** `review.overall`·`review.sameCountry` 모두 기존과 동일한 실제 수치가 내려온다.

---

### User Story 2 - 비회원 응답임을 위험도 null 로 판별한다 (Priority: P2)

비회원은 기피성분 프로필이 없으므로 위험도 판별 자체가 성립하지 않는다. 지금은 이 경우가 "SAFE"(안전)로 내려가 "위험 없음"과 "판별 안 함"이 구분되지 않는다. 개편 후 비회원의 `overallRiskStatus` 는 `null` 로 내려가고, 클라이언트는 이 값을 비회원 조회 응답의 판별 기준으로 삼는다.

**Why this priority**: SAFE 오표시는 안전 정보의 오해 소지가 있고, 클라이언트가 비회원 UI 분기를 할 단일 기준이 필요하다.

**Independent Test**: 인증 없이 상세 조회 시 `overallRiskStatus` 가 null, 인증 시 기존 값(SAFE/CAUTION/DANGER/UNKNOWN)인지 확인.

**Acceptance Scenarios**:

1. **Given** 임의의 READY 음식, **When** 비회원이 상세를 조회하면, **Then** `overallRiskStatus` 는 `null` 이다.
2. **Given** 기피성분이 음식 성분과 겹치는 회원, **When** 상세를 조회하면, **Then** `overallRiskStatus` 는 기존 정책 그대로(겹친 성분 위험도의 최악값) 내려온다.
3. **Given** 비회원 조회, **When** 응답을 받으면, **Then** `bookmarked` 는 기존과 동일하게 `false` 다 (null 아님).

---

### User Story 3 - blur 필드 제거 (Priority: P3)

리뷰 요약의 `blur` 필드는 "비회원이라 가렸다"를 알리는 용도였는데, 비회원에게도 실수치를 제공하게 되면서 존재 이유가 사라진다. 회원·비회원 응답 모두에서 `blur` 필드를 제거한다.

**Why this priority**: 죽은 필드 정리 — 기능 가치는 없지만 계약을 깨끗하게 유지한다.

**Independent Test**: 회원·비회원 상세 조회 응답 JSON 에 `blur` 키가 없는지 확인.

**Acceptance Scenarios**:

1. **Given** 회원 또는 비회원, **When** 상세를 조회하면, **Then** `review` 객체에 `blur` 필드가 존재하지 않는다.

---

### User Story 4 - 음식 재료 전체 공개 + 사용자 교집합 필드 분리 (Priority: P1, 2026-08-18 확장)

상세 화면이 음식의 **재료 전체 목록**(코드·번역 표시명·포함 확률)을 회원·비회원 공통으로 보여주고, 조회자 회피성분과의 **교집합은 별도 필드**(코드·위험도)로 내려 UI 가 재료 목록 위에 사용자 맥락을 얹는다. 기존 `ingredients`(교집합 전용) 계약을 재편한다.

**Acceptance Scenarios**:

1. **Given** 재료 3종(SOY 100%·CLAM 50%·WHEAT 5%)인 음식, **When** 누구든 상세를 조회하면, **Then** `ingredients` 는 확률 내림차순 3건 — 각 항목 `{code, name(요청 언어 번역), inclusionPercent}`.
2. **Given** SOY·CLAM 회피 회원, **When** 상세를 조회하면, **Then** `avoidedIngredients` 는 `[{code: SOY, riskStatus: DANGER}, {code: CLAM, riskStatus: CAUTION}]` (확률 내림차순, 확률 기반 위험도).
3. **Given** 비회원, **When** 상세를 조회하면, **Then** `avoidedIngredients` 는 `null` 이다 (빈 배열 아님 — 회피 없음(빈 배열)과 판별 안 함(null)을 구분).

---

### User Story 5 - 최근 리뷰 5개 동봉 + 리뷰 필드 개편 (Priority: P1, 2026-08-18 확장)

상세 응답이 음식의 **최신 리뷰 최대 5개**를 함께 내려 별도 목록 호출 없이 첫 화면을 그린다. 항목 형태는 리뷰 목록 API 의 `ReviewResponse` 와 동일(클라이언트가 이미 아는 계약). 요약 필드는 `review` → `reviewSummary` 로 개명해 목록(`recentReviews`)과 구분한다.

**Acceptance Scenarios**:

1. **Given** 리뷰 7건인 음식, **When** 상세를 조회하면, **Then** `recentReviews` 는 최신순 5건이고 각 항목은 리뷰 목록 API 와 같은 형태다.
2. **Given** 비회원 조회, **When** 응답을 받으면, **Then** `recentReviews` 의 `likedByMe` 는 전부 false 다.
3. **Given** 임의 조회, **When** 응답을 받으면, **Then** 요약은 `reviewSummary` 키로 내려가고 `review` 키는 존재하지 않는다.

---

### Edge Cases

- 국적이 없는(미설정) **회원**이 조회하면? — 기존 동작 유지: `sameCountry` 는 기본값 `{0.0, 0}` (회원 응답은 blur 제거 외 불변이라는 원칙). null 은 비회원 전용 시그널로 남긴다.
- 비회원 조회 시 `ingredients` 목록은? — **빈 배열 유지(결정)**. `ingredients` 는 "조회자 회피성분과의 교집합" 의미이므로 회피 프로필이 없는 비회원은 빈 배열이 자연스럽다. 성분 전체 공개는 이 기능 범위 밖.
- 구버전 앱이 `blur` 필드를 필수로 파싱하고 있다면? — **무버전 매핑 즉시 변경(결정)**. 이 변경은 클라이언트 요청으로 조율된 것이며, 필드 제거·null 허용은 관대한 파싱에서 안전하다. 별도 X-API-Version 버전을 만들지 않는다.
- 비회원이 UNREADY(콘텐츠 미완성) 음식을 조회하면? — 기존과 동일: 404(FOOD_NOT_FOUND). 이 기능의 범위 밖.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 비회원의 음식 상세 조회 응답에서 `overallRiskStatus` 는 `null` 이어야 한다. 회원 응답의 위험도 산출 정책은 불변이다.
- **FR-002**: 비회원의 음식 상세 조회 응답에서 `bookmarked` 는 `false` 를 유지해야 한다.
- **FR-003**: 비회원의 음식 상세 조회 응답에서 `review.overall` 은 회원과 동일한 실제 집계값(평균 별점·리뷰 수)이어야 한다.
- **FR-004**: 비회원의 음식 상세 조회 응답에서 `review.sameCountry` 는 `null` 이어야 한다. 국적 보유 회원의 `sameCountry` 는 기존 수치 그대로다.
- **FR-005**: 회원·비회원 공통으로 `review` 객체에서 `blur` 필드를 제거해야 한다.
- **FR-006**: 위 변경은 음식 상세 단건 조회에 한정한다 — 목록/검색 응답의 비회원 처리(북마크 false·요약 정책)는 불변이다.
- **FR-007** (2026-08-18): `ingredients` 는 음식 재료 **전체** 목록으로 재정의한다 — 항목 `{code, name(요청 언어), inclusionPercent}`, 확률 내림차순, 회원·비회원 공통.
- **FR-008** (2026-08-18): 조회자 회피 교집합은 `avoidedIngredients` — 항목 `{code, riskStatus}`, 회원은 배열(겹침 없으면 빈 배열), 비회원은 `null`.
- **FR-009** (2026-08-18): 리뷰 요약 필드는 `review` → `reviewSummary` 로 개명하고, `recentReviews` 에 최신순 최대 5개 리뷰를 리뷰 목록 API 의 `ReviewResponse` 형태로 동봉한다. 비회원의 `likedByMe` 는 false, 차단/신고 제외는 회원 조회에만 적용한다.
- **FR-010** (2026-08-18 2차): 음식 고유 정보(name·koreanName·imageRef·description·spiciness·ingredients)는 `food` 객체로 묶는다 — 조회자 맥락 필드(overallRiskStatus·avoidedIngredients·bookmarked)·리뷰 필드와 층을 분리.
- **FR-011** (2026-08-18 2차): `recentReviews` 항목에서 `food` 필드는 생략한다(이 음식에 대한 리뷰라 중복). `ReviewResponse` 공통으로 — `createdAt` 은 epoch millis(long), `author` 에 `profileImageUrl` 을 포함한다(리뷰 목록·작성·수정 응답에도 동일 적용).

### Key Entities

- **음식 상세 응답**: 음식 이름·설명·맵기·이미지 + 조회자 맥락 필드(위험도·북마크·리뷰 요약). 조회자 맥락 필드가 이번 변경 대상.
- **리뷰 요약**: 전체(overall)·같은 국적(sameCountry) 두 축의 평균 별점·리뷰 수. sameCountry 는 "조회자 국적" 이 있어야 성립하는 파생 정보.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 비회원이 음식 상세에서 실제 리뷰 평판(평균 별점·리뷰 수)을 확인할 수 있다 — 가림(0 고정) 응답 0건.
- **SC-002**: 비회원 응답에서 "안전(SAFE)" 오표시가 발생하지 않는다 — 위험도는 null 로만 내려간다.
- **SC-003**: 클라이언트가 응답 본문만으로(별도 인증 상태 조회 없이) 비회원 조회 여부를 판별할 수 있다.
- **SC-004**: 회원 조회 응답은 blur 필드 제거 외에 어떤 값도 변하지 않는다 — 기존 회원 시나리오 회귀 0건.

## Assumptions

- 클라이언트(앱)는 이 계약 변경의 요청 주체로, 응답 스키마 변경(blur 제거·sameCountry nullable)을 수용할 준비가 되어 있다.
- `overallRiskStatus: null` 을 비회원 판별 기준으로 쓰는 것은 클라이언트 결정이며, 서버는 "비회원 = 위험도 미판별(null)" 계약만 보장한다.
- 국적 없는 회원의 `sameCountry` 기본값(`{0.0, 0}`) 동작은 기존 그대로 둔다 — null 은 비회원 전용.
- 음식 목록·검색·북마크 목록 등 다른 화면의 비회원 정책은 이 기능의 범위 밖이다.
