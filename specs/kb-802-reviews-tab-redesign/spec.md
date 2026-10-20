# Feature Specification: 리뷰 단건 조회 · 리뷰 음식 요약 한국어명 (KB-802 BE)

**Feature Branch**: `feat/kb802-reviews-tab-redesign`
**Created**: 2026-10-16
**Status**: Ready
**Input**: `_workspace/kb802-reviews-tab-redesign/01_cto_plan.md` "BE 작업" 절 + 계약 C-1·C-2, PRD 수용 기준

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 리뷰 상세를 한 건만으로 연다 (Priority: P1)

앱 리뷰 탭에서 카드를 누르거나 딥링크로 들어오면 리뷰 상세 화면이 열린다. 상세는 리스트 캐시가 없어도(딥링크·재진입) 리뷰 한 건을 서버에서 받아 리스트 항목과 같은 모양으로 그린다. 비회원도 볼 수 있다.

**Why this priority**: 상세 화면이 캐시에만 의존하던 구조(KB-307 삭제 사유)를 없애는 근본 조건이다.

**Independent Test**: 비회원·회원으로 리뷰 id 를 조회해 리스트 항목과 같은 필드 집합을 받는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 공개된 리뷰, **When** 비회원이 lang 과 함께 조회, **Then** 성공 응답에 리뷰 전체 필드·음식 요약이 오고 likedByMe=false 다.
2. **Given** 회원이 좋아요를 누른 리뷰, **When** 그 회원이 조회, **Then** likedByMe=true, likeCount 가 실제 수와 같다.
3. **Given** 같은 리뷰, **When** 단건과 목록을 같은 lang 으로 조회, **Then** 두 응답의 해당 항목 필드 집합이 같다.

---

### User Story 2 - 볼 수 없는 리뷰는 "없음" 으로 답한다 (Priority: P1)

삭제됐거나 존재하지 않거나 조회자가 차단한 작성자의 리뷰, 음식이 삭제된 타인 리뷰는 모두 같은 "리뷰 없음" 코드로 답한다. 본인 리뷰는 차단·음식 판정과 무관하게 보인다.

**Independent Test**: 각 분기를 시드하고 REVIEW-001 을 받는지 본다.

**Acceptance Scenarios**:

1. **Given** 삭제된 리뷰 또는 없는 id, **When** 조회, **Then** REVIEW-001.
2. **Given** 조회자가 작성자를 차단, **When** 조회, **Then** REVIEW-001. 작성자 본인은 성공.
3. **Given** 음식이 삭제된 리뷰, **When** 타인이 조회, **Then** REVIEW-001.

---

### User Story 3 - 음식 칩에 한국어명 부제를 단다 (Priority: P2)

리뷰 응답의 음식 요약에 한국어 표시명이 함께 온다. 요청 언어가 한국어여서 이름이 같으면 비운다.

**Independent Test**: lang=en·ko 로 목록·단건을 조회해 koreanName 을 비교한다.

**Acceptance Scenarios**:

1. **Given** 영어 번역이 있는 음식의 리뷰, **When** lang=en 조회, **Then** food.koreanName = 한국어명.
2. **Given** 같은 리뷰, **When** lang=ko 조회, **Then** food.koreanName = null.

### Edge Cases

- lang 누락·reviewId 가 숫자가 아님 → COMMON-002.
- `/api/reviews/me` 는 단건 경로로 해석되지 않고 계속 인증 필수다.
- 번역이 없어 요청 언어명이 한국어로 폴백되면 koreanName 은 null 이다(이름과 같으므로).
- 토큰이 있는데 무효하면 기존 목록과 같은 인증 오류.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001** (R-01): 리뷰 id 와 표시 언어로 리뷰 한 건을 조회한다. 응답 모양은 리스트 항목과 같고 음식 요약을 항상 채운다(본인 리뷰의 삭제된 음식만 생략). 비회원 허용.
- **FR-002** (R-02): 가시성 판정은 기존 리뷰 번역 API 와 같다. 볼 수 없으면 REVIEW-001(HTTP 400 유지).
- **FR-003** (R-03): 리뷰 음식 요약에 koreanName 을 더한다. 음식 목록 응답과 같은 규칙(한국어 표시명이 요청 언어명과 같으면 null). 필드 추가만이며 버전 분기 없음.
- **FR-004**: 기존 목록(`GET /reviews`)·내 리뷰의 정렬·페이징·다른 필드는 바뀌지 않는다.

### Key Entities

- **Review**, **Food** — 기존 엔티티. 스키마 변경 없음.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 상세 화면이 캐시 없이 리뷰 한 건 요청 1회로 완성 렌더 데이터를 받는다.
- **SC-002**: 볼 수 없는 리뷰 5가지 경우 모두 같은 코드 하나로 구분된다.
- **SC-003**: 기존 목록 응답의 필드 값은 koreanName 추가 외에 하나도 바뀌지 않는다.

## Assumptions

- REVIEW-001 은 HTTP 400 그대로(cto 가정 A-1).
- 응답 봉투는 기존 BaseResponse(`success`·`payload`·`message`·`code`). 계획 예시의 `data` 는 `payload` 를 뜻한다.
- 마이그레이션 없음.
