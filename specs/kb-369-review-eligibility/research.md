# Research: KB-369 리뷰 작성 자격 검증

## Decision 1 — 자격 판정 데이터 소스

- **Decision**: `scan_history` 테이블에 (member_id, food_id) exists 파생 쿼리. 활성(status=ACTIVE)은 `@SQLRestriction` 자동 적용, 매칭 실패 스캔(food_id NULL)은 조건에 자연 배제.
- **Rationale**: "스캔한 음식만 리뷰"의 원천 데이터가 scan_history 뿐이다. 별도 자격 테이블·플래그는 이중 장부.
- **Alternatives considered**: 회원별 스캔 음식 캐시(Redis) — 쓰기 경로 복잡화 대비 이득 없음(회원당 이력 소규모). 기각.

## Decision 2 — 에러 코드·상태

- **Decision**: `REVIEW_NOT_ELIGIBLE("REVIEW-004", 403)`.
- **Rationale**: 채번 규칙(도메인 접두+3자리, 폐기 번호 재사용 금지)상 다음 번호. 인증됐으나 자격 없음 = 403(REVIEW-002 와 같은 축). FE 는 코드로만 분기(메시지 매칭 금지 규약).
- **Alternatives considered**: 400 — 요청 형식 오류가 아니라 부적합. 기각.

## Decision 3 — 검증 위치와 순서

- **Decision**: `ReviewService.createReview` 안, `getReadyFood` 다음·이미지 검증 앞. `updateReview` 는 무변경.
- **Rationale**: 음식 오류(FOOD-001) 우선(FR-006). 수정은 본인 리뷰 검증(getMyReview)이 작성 시점 자격을 전이 보장 — 재검증하면 사후 이력 변동에 본인 리뷰 수정이 막히는 부작용만 생긴다(사용자 확인 완료).
- **Alternatives considered**: 컨트롤러 검증 — 도메인 정책이므로 서비스 소유가 규약. 기각.

## Decision 4 — 상세 응답 필드

- **Decision**: `reviewEligible: Boolean?` — 회원 true/false, 비회원 null(overallRiskStatus 와 같은 비회원 판별 축). is 접두 없는 기존 Boolean 응답 컨벤션(`bookmarked`·`likedByMe`) 준수. 명칭은 클라이언트에 제안 후 확정(스캔 여부가 아니라 자격 의미 — 규칙이 바뀌어도 이름이 안 낡음).
- **Rationale**: 진입 시점 게이트(US2). 작성 검증과 같은 쿼리를 써서 FR-005(정합)를 구조로 보장.
- **Alternatives considered**: `scannedByMe`(클라이언트 원안) — 자격 의미로 대체 제안. `isReviewWritable` — is 접두가 응답 컨벤션 위반. 기각.

## Decision 5 — 인덱스

- **Decision**: 신규 인덱스 없음.
- **Rationale**: exists 조회는 `idx_scan_history_recent(member_id, created_at)` 의 member_id 프리픽스로 회원당 이력(수십 건)만 확인. 상세 조회 1회당 1쿼리 추가는 기존 패턴(북마크 exists 등)과 동급.
- **Alternatives considered**: `(member_id, food_id)` 복합 인덱스 — 실측 병목 확인 전 선행 추가는 과잉. 후속으로 보류.
