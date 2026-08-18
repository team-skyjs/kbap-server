# Feature Specification: 리뷰 목록 조회 API 비회원 공개

**Feature Branch**: `kb-348-guest-review-list`

**Created**: 2026-08-18

**Status**: Draft

**Input**: User description: "KB-348 — 리뷰 목록 조회 API 비회원 공개. agent-hub(리뷰 노출 규칙·목록 계약, 2026-08-15 기획 멘토링 변경 안건)를 참고해 맥락 파악 후 작업."

## 맥락 (agent-hub·KB-334 선행 작업)

- 8/15 기획 멘토링 결정: **"비회원: 리뷰 전부 공개 열람 가능, 스캔 불가"** — 비회원 콘텐츠 개방 축의 하나.
- KB-334(PR #167)에서 음식 상세의 `recentReviews`(최대 5개)는 이미 비회원에게 열렸다 — 그 이상(전체 목록·피드)은 여전히 회원 전용이라 "5개 더 보기" 흐름이 비회원에서 끊긴다.
- 현재 `GET /api/reviews` 는 두 겹으로 막혀 있다: JWT 보호 경로 등록(`/api/reviews`·`/api/reviews/*`) + 필수 인증 바인딩. 보호 경로는 서블릿 필터 URL 패턴 단위라 **HTTP 메서드를 구분하지 않는다** — 같은 경로의 작성(POST)은 회원 전용으로 남아야 한다.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 비회원이 리뷰 목록을 본다 (Priority: P1)

로그인하지 않은 사용자가 음식 상세의 리뷰 더보기(또는 리뷰 피드)로 진입하면, 회원과 동일한 리뷰 목록(전체 또는 음식별, 커서 페이징)을 볼 수 있다. 조회자 맥락 필드(`likedByMe`)만 비회원 기본값(false)으로 내려간다.

**Why this priority**: 이 기능의 전부 — 비회원 콘텐츠 개방으로 온보딩 전 가치 전달.

**Independent Test**: 인증 없이 `GET /api/reviews?foodId=1&lang=en` 호출 → 200 + 회원과 동일한 목록. `GET /api/reviews?lang=en`(전체 피드)도 동일.

**Acceptance Scenarios**:

1. **Given** 리뷰가 있는 음식, **When** 비회원이 `GET /api/reviews?foodId={id}&lang=en` 을 호출하면, **Then** 200 과 함께 회원 조회와 동일한 목록(최신순 커서 20건)이 내려가고 각 항목의 `likedByMe` 는 false 다.
2. **Given** 서비스 전체 리뷰, **When** 비회원이 `foodId` 없이 호출하면, **Then** 전체 피드가 동일 규칙으로 내려간다.
3. **Given** 비회원 조회, **When** `countryCode` 필터를 지정하면, **Then** 회원과 동일하게 그 국적 작성 리뷰만 내려간다 (파라미터 의미 불변).
4. **Given** 비회원 조회, **When** 커서로 다음 페이지를 요청하면, **Then** keyset 페이징이 회원과 동일하게 동작한다.

---

### User Story 2 - 회원 전용 동작은 그대로 막혀 있다 (Priority: P1)

리뷰 열람만 열리고 나머지는 불변이다: 작성(POST /api/reviews)·수정·삭제·좋아요·내 리뷰(GET /api/reviews/me)는 여전히 인증 없이는 401 이다.

**Why this priority**: 같은 경로(`/api/reviews`)에서 GET 만 여는 것이 이 작업의 핵심 난점 — 보호가 풀리는 순간 스팸 작성이 가능해진다.

**Independent Test**: 인증 없이 POST /api/reviews·GET /api/reviews/me·POST /api/reviews/{id}/like 호출 → 전부 401.

**Acceptance Scenarios**:

1. **Given** 비회원, **When** `POST /api/reviews` 로 리뷰를 작성하려 하면, **Then** 401 이다.
2. **Given** 비회원, **When** `GET /api/reviews/me` 를 호출하면, **Then** 401 이다.
3. **Given** 비회원, **When** 수정(PATCH)·삭제(DELETE)·좋아요(POST like)를 호출하면, **Then** 전부 401 이다.
4. **Given** 회원, **When** 기존 모든 리뷰 동작을 수행하면, **Then** 종전과 완전히 동일하다 (차단·신고 제외 포함).

---

### Edge Cases

- 비회원 조회의 차단·신고 제외는? — 비회원에게는 차단·신고 개념이 없으므로 **제외 없이 전량 노출** (KB-334 recentReviews 와 동일 규칙 — 조회자별 필터는 회원에만 적용).
- 탈퇴 회원 토큰(유효하지만 활성 회원 아님)으로 조회하면? — 목록 조회는 성공하되 비회원과 동일 취급(차단·신고 제외 없음, likedByMe false). 기존 상세 recentReviews 취급과 일관.
- 소프트 삭제된 음식의 리뷰는? — 기존 규칙 그대로 전원 제외 (`exists Food`).
- `lang` 누락·빈 값은? — 기존 규칙 그대로 400 (비회원도 동일).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 비회원은 `GET /api/reviews`(전체·`foodId` 지정·`countryCode` 필터·커서 페이징 포함)를 인증 없이 호출할 수 있어야 하며, 응답 형태는 회원 조회와 동일하다.
- **FR-002**: 비회원 조회의 `likedByMe` 는 항상 false 다. 차단·신고에 따른 조회자별 제외는 적용하지 않는다(개념 미성립).
- **FR-003**: 리뷰 작성·수정·삭제·좋아요·`GET /api/reviews/me` 는 계속 인증 필수(미인증 401)다 — 같은 경로의 GET 개방이 이들 보호를 약화시켜서는 안 된다.
- **FR-004**: 회원의 리뷰 목록 조회 동작(차단·신고 제외, likedByMe, 커서, 정렬)은 변경되지 않는다.
- **FR-005**: 응답 계약(항목 형태·페이징 봉투)은 변경하지 않는다 — 인증 요구만 바뀐다.

### Key Entities

- **리뷰 목록**: 기존 `GET /api/reviews` 계약(항목 = 리뷰 목록 API 형태, 커서 페이징) 그대로 — 이 기능은 데이터·형태가 아니라 **접근 범위**를 바꾼다.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 비회원이 로그인 없이 리뷰 목록(전체·음식별)을 끝까지 페이징하며 열람할 수 있다.
- **SC-002**: 인증 없는 쓰기 계열 요청(작성·수정·삭제·좋아요)과 내 리뷰 조회는 100% 401 로 거절된다 — 회귀 0건.
- **SC-003**: 회원의 목록 조회 결과는 이 변경 전후로 완전히 동일하다 — 기존 시나리오 회귀 0건.

## Assumptions

- 8/15 기획의 "비회원 리뷰 전부 공개 열람"이 근거 결정이며, 클라이언트는 비회원 화면에서 좋아요 버튼을 로그인 유도로 처리한다(서버는 likedByMe=false 만 보장).
- KB-334 에서 공용화한 비회원 조회 지원(조회자 nullable 조립)이 재사용 가능한 상태다.
- 계약 변경이 없으므로 새 X-API-Version 을 만들지 않는다(접근 제어 완화는 구 클라이언트에 무해).
- 리뷰 정렬·필터 확장(8/15 기획의 별도 안건)은 이 기능의 범위 밖이다.
