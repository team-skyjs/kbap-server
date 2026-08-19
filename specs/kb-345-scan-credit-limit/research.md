# Research: 회원 스캔 무료 3회·리뷰 해금

## R1. 판정 상태 모델 — `scan_unlocked` 저장 플래그

- **Decision**: `member.scan_unlocked BOOLEAN NOT NULL DEFAULT false` 신설. 허용 판정 = `scanUnlocked || scanCount < 3`. 해금은 리뷰 작성 시 플래그를 세우고, 재잠금(리뷰 0건 검사·회수)은 별도 태스크의 배치가 이 플래그를 내리는 방식으로 소비한다.
- **Rationale**: 실시간 `reviewCount > 0` 판정이면 삭제 즉시 재잠금이 되어 "재잠금은 배치만" 결정과 어긋난다 — 저장 플래그가 그 결정의 전제. `scanCount` 는 기존 랭킹 카운터를 그대로 판정에 재사용(성공 스캔 누적과 의미 일치 — 별도 카운터 이중화 불필요).
- **Alternatives considered**: 실시간 reviewCount 판정(컬럼 無) — 삭제 즉시 재잠금이라 결정 위배. 별도 크레딧 테이블 — 고정 한도 3회에 과설계.

## R2. 해금 시점 — 리뷰 카운트 원자 UPDATE 에 동승

- **Decision**: `MemberJpaRepository.increaseReviewCount` 의 원자 UPDATE 에 `scanUnlocked = true` 를 함께 세운다.
- **Rationale**: 리뷰 작성 경로가 이미 이 UPDATE 를 반드시 지나므로(작성 = 카운트 증가) 별도 호출·트랜잭션 없이 즉시·원자 해금(SC-003). 놓칠 수 있는 다른 작성 경로가 없다.
- **Alternatives considered**: ReviewService 에서 별도 unlock 호출 — 호출 누락 여지·UPDATE 2회. 기각.

## R3. 판정 위치·에러 코드

- **Decision**: `ScanService.scan` 공통 본체 초입(member 로드 직후, 이미지 검증·비전 호출 전)에서 판정 — v1·v2 공통(FR-001)·비용 0(FR-003). 에러는 신규 `SCAN_LIMIT_EXCEEDED("SCAN-004", 403, "무료 스캔 횟수를 모두 사용했습니다. 리뷰를 작성하면 무제한으로 이용할 수 있어요")`.
- **Rationale**: scan() 이 v1/v2 유일 경로라 한 곳 판정으로 충분. 403 은 인증됐지만 이용 자격이 없는 상태(REVIEW_FORBIDDEN 403 선례). 메시지가 곧 해제 방법 안내이고 클라이언트는 code 로 분기.
- **Alternatives considered**: 컨트롤러 판정 — v1/v2 두 곳 중복. 400 — 요청 잘못이 아니라 자격 문제라 403 이 정확.

## R4. 카운트·동시성

- **Decision**: 카운트 증가 시점은 현행 유지(스캔 성공 후 `increaseScanCount`) — 실패 미소모(FR-004)는 기존 구조가 이미 보장. 판정→성공 사이 동시 요청으로 3회를 1~2회 초과할 수 있으나 감수(헌법 — 비치명 경합, 격리수준 무조정).
- **Rationale**: 성공에만 실비용(LLM)이 발생하므로 성공 차감이 정당하고, 초과 경합의 손실은 스캔 1~2회 비용뿐이다.

## R5. 소급·마이그레이션

- **Decision**: `DEFAULT false` 단순 컬럼 추가 — 기존 회원 백필 없음(확정). 배포 즉시 정책 일괄 적용: 기존 성공 스캔 3회 이상·해금 안 된 회원은 잠기고, 새 리뷰 작성으로 해금.
- **Rationale**: 사용자 확정(백필 쿼리 금지). additive DEFAULT 컬럼이라 블루/그린 안전.

## R6. 범위 밖(별도 태스크)

- 재잠금 배치(리뷰 0건 해금 회원 주기 검사·`scan_unlocked=false` 회수) — 이 기능이 남긴 플래그를 소비하는 후속 태스크. 잔여 횟수 조회 API·기기/IP 단위 방어·크레딧 결제도 범위 밖.
