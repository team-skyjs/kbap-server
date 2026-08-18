# Research: 리뷰 평가 항목 추가 — 제공 속도·직원 친절도

## R1. 범위 — 3종 → 2종 축소

- **Decision**: 제공 속도(servingSpeed)·직원 친절도(staffKindness) 2개만 추가한다. "매장 찾기 쉬움"과 음식 단위 집계는 후속 이슈.
- **Rationale**: plan 지시(2026-08-18)로 확정 — Jira KB-347 제목(3종)은 갱신 권장.
- **Alternatives considered**: 스펙 초안대로 3종 — 사용자 확인으로 기각.

## R2. 값 표현 — 0~5 단일 규약(null 미사용)

- **Decision**: 요청·저장·응답 전부 0~5 정수. 0 = 평가 안 함. 요청에서 필드 누락 시 0 으로 해석. 응답은 항상 숫자(null 없음).
- **Rationale**: 사용자 확정("미기입은 없음. 0점으로 보내줄 거임"). 클라이언트가 단일 규약으로 양방향 처리하고, DB 도 `NOT NULL DEFAULT 0` 으로 기존 행 백필이 필요 없다.
- **Alternatives considered**: 미기입 null(값/없음 타입 구분) — 입력 규약(0)과 비대칭이라 기각.

## R3. 컬럼·엔티티 설계

- **Decision**: `food_review` 에 `serving_speed_rating TINYINT NOT NULL DEFAULT 0`, `staff_kindness_rating TINYINT NOT NULL DEFAULT 0` 추가. `Review` 엔티티에 `var servingSpeedRating: Int = 0`·`var staffKindnessRating: Int = 0` — `requireValid` 에 0..5 검증 추가, `update()` 시그니처에 두 파라미터 추가.
- **Rationale**: 기존 `rating TINYINT` 과 동형. DEFAULT 0 이라 기존 행·기존 INSERT(테스트 시드 포함) 모두 무변경으로 동작 — 블루/그린 배포 중 구 리비전이 신 스키마 위에서 돌아도 안전(additive+default, [[schema-change-revision-coexistence]]).
- **Alternatives considered**: 별도 평가 테이블(리뷰 1:1) — 항목 2개에 조인 비용만 얹는 과설계라 기각. nullable 컬럼 — R2 로 기각.

## R4. API 계약 — additive 필드, 무버전

- **Decision**: `ReviewCreateRequest`·`ReviewUpdateRequest` 에 `servingSpeed`·`staffKindness`(Int?, `@Min(0)` `@Max(5)`, null→0) 추가. `ReviewResponse` 에 `servingSpeed`·`staffKindness`(Int, 항상 0~5) 추가 — 목록·상세 동봉·내 리뷰·작성/수정 응답 전 경로가 `ReviewResponse.from` 하나를 지나므로 조립 변경은 한 곳이다. `X-API-Version` 버전 증가 없음.
- **Rationale**: KB-334 선례(클라이언트 조율된 additive 변경은 무버전 즉시 적용). 응답 필드명은 요청과 동일하게 `servingSpeed`/`staffKindness` — 엔티티 프로퍼티(`servingSpeedRating`)와 달리 API 는 항목명 자체가 평가임이 자명해 접미사 생략.
- **Alternatives considered**: 중첩 객체(`extraRatings: {...}`) — 항목 2개에 봉투만 얹는다. 버전 분기(1.1+) — 계약 파괴가 없어 불필요.

## R5. 검증 위치

- **Decision**: 범위 검증(0~5)은 요청 DTO(`@Min`/`@Max`)가 1차 소유하고, 엔티티 `requireValid` 가 동일 범위를 최종 강제한다(기존 rating 1~5 패턴 그대로).
- **Rationale**: 헌법 V — 유효성 검증은 요청 경계가 소유. 엔티티 require 는 기존 rating·content 와 동일한 도메인 불변 계층.
- **Alternatives considered**: 서비스 계층 검증 — 기존 패턴에 없던 계층 추가라 기각.

## R6. 집계·조회 쿼리 영향

- **Decision**: `aggregateRating`·`aggregateRatingsByFoodIds`·`findReviewPage` 등 기존 쿼리는 손대지 않는다.
- **Rationale**: FR-005·FR-007 — 별점 집계 불변, 항목 집계는 범위 밖. select r(엔티티 전체) 조회라 컬럼 추가만으로 응답 경로에 값이 실린다.
- **Alternatives considered**: 없음.
