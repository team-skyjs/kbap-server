# Feature Specification: 화제 영상 K-푸드 전용 목록 API (idol-picks)

**Feature Branch**: `feat/kb836-home-idol-kfood-section`

**Created**: 2026-10-21

**Status**: Draft

**Input**: User description: "음식별 화제 영상 매핑 테이블(food_idol_video)과 29종·36행 시드를 추가하고, 전용 엔드포인트 GET /api/foods/idol-picks?lang= 로 음식 요약 + 대표 영상 {url, platform} 을 내린다 (Jira KB-836). 홈·상세 API 는 바꾸지 않는다. 이름·제목·썸네일·사유는 저장·응답하지 않는다."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 화제 영상이 있는 음식 목록 받기 (Priority: P1)

앱의 `/idol-picks` 목록 화면이 화제 영상이 연결된 완성 음식과 각 음식의 대표 영상 링크를 한 번에 받는다.

**Why this priority**: 목록 화면과 영상 열기의 유일한 데이터 원천이다.

**Independent Test**: 매핑 행을 시드하고 `GET /api/foods/idol-picks?lang=en` 을 호출해 순서·항목 형태를 확인한다.

**Acceptance Scenarios**:

1. **Given** 매핑된 READY 음식들, **When** 목록 조회, **Then** 음식별 대표 영상(ACTIVE 중 position 최소)의 hype desc → published_month desc → foodId asc 순으로 음식당 1건 내려간다.
2. **Given** position 2 영상의 hype 가 더 높은 음식, **When** 조회, **Then** 정렬·`video` 는 position 1 영상 기준이다.
3. **Given** 비READY 음식·삭제 음식·DELETED 행만 있는 음식, **When** 조회, **Then** 목록에서 빠진다. DELETED 행은 대표 선정에서도 제외된다.
4. **Given** 매핑 0건, **When** 조회, **Then** `{ "items": [] }`.
5. **Given** 토큰 없음, **When** 조회, **Then** 200, `bookmarked=false`, 위험도는 기존 목록의 게스트 값.
6. **Given** 기피 성분·북마크가 있는 회원 토큰, **When** 조회, **Then** `overallRiskStatus`·`bookmarked` 가 회원 기준이다.
7. **Given** `lang` 누락 또는 X-API-Version 헤더 누락, **When** 조회, **Then** 400 `COMMON-002`.

### User Story 2 - 기존 API 무변 (Priority: P1)

**Acceptance Scenarios**:

1. **Given** 매핑 행이 있음, **When** 홈(1.3)·상세 조회, **Then** `idolPickFoods`·`idolVideo` 키가 없다.

### Edge Cases

- 같은 음식 여러 행 → 1건.
- 시드 대상 음식이 카탈로그에 없는 환경 → 그 행만 삽입되지 않는다(이름 기준 INSERT … SELECT).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `food_idol_video` 테이블은 데이터 모델 10개 컬럼만 가진다(이름·제목·썸네일·사유 없음).
- **FR-002**: 마이그레이션은 계획 시드 표 29종·36행을 `korean_name` 기준으로 삽입한다. 숫자 id 하드코딩 금지.
- **FR-003**: `GET /api/foods/idol-picks` 의 payload 키는 정확히 `items`, 항목 키는 음식 카드 9키 + `video`, `video` 키는 `url, platform` 이다.
- **FR-004**: 정렬·대표 선정은 US1 시나리오 1·2. READY·ACTIVE 음식과 ACTIVE 행만.
- **FR-005**: 응답 어디에도 `title,thumbnail,idol,artist,group,reason,hype,position,publishedMonth` 키가 없다.
- **FR-006**: 버전 분기 없음(헤더 필수·값 무관). 게스트 허용, WebConfig URL 패턴·게스트 예외 무변.
- **FR-007**: 홈·상세 API 응답과 코드는 바뀌지 않는다.

### Key Entities

- **FoodIdolVideo**: foodId, platform(YOUTUBE|TIKTOK), videoUrl, publishedMonth, hype(1~5), position(1 = 대표), status.

## Success Criteria *(mandatory)*

- **SC-001**: R-19 4항목(키 집합·금지 키·스키마 컬럼·홈/상세 회귀) 통합 테스트 통과.
- **SC-002**: dev 적용 후 29개 음식·36행(QA 대조).

## Assumptions

- 계획 `01_cto_plan.md` D5·D7~D11·가정 절 그대로. Shorts URL 은 YOUTUBE. 비READY 5종은 시드하지 않는다(D10).
