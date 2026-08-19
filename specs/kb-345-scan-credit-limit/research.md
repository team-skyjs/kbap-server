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

## R4. 카운트·동시성 — (재개정 2026-08-19: 원자 선점 + 보상)

- **초안 폐기**: "판정(read) 후 성공 시 카운트" 구조는 판정~카운트 사이에 LLM 호출(수 초)이 껴서, 동시 버스트 N개가 전부 게이트를 통과해 **무제한 유료 스캔**이 가능하다는 리뷰 지적으로 폐기 — "1~2회 초과 감수" 전제가 창의 길이를 과소평가했다.
- **Decision**: 비전 호출 **전에 조건부 원자 UPDATE 로 선점**한다 — `update member set scan_count = scan_count + 1 where id = ? and (scan_unlocked or scan_count < :limit)`, 0행이면 403 SCAN-004. 실패 경로(비전 실패·비메뉴판 등)는 **보상 UPDATE(-1, `scan_count > 0` 가드)** 로 반환 — 실패 미소모(FR-004)는 순효과로 유지된다. 선점·보상은 각각 독립된 짧은 트랜잭션이고 LLM 호출은 트랜잭션 밖(사가의 최소형 — 로컬 커밋 + 보상).
- **분산 유효성**: 조정자가 앱 인스턴스가 아니라 공유 MySQL — 행 잠금이 전 커넥션을 직렬화하므로 운영 api 2대에서도 성립. 인스턴스 로컬 상태 없음.
- **감수**: 보상 전 프로세스 크래시 시 무료 1회 유실(빈도 극저·피해 1회 — 복구 인프라 불가). 처리 중 선점분이 scan_count 에 잠깐 반영(랭킹 순간 +1).
- **Alternatives considered**: 비관 잠금으로 스캔 전체 직렬화 — 외부 호출을 트랜잭션·잠금 안에 가둬 커넥션 고갈(헌법 위배). 낙관 @Version — 같은 효과를 더 복잡하게. Redis 분산락 — DB 원자 UPDATE 로 충분한 문제에 인프라 추가. 전부 기각.

## R5. 소급·마이그레이션

- **Decision**: `DEFAULT false` 단순 컬럼 추가 — 기존 회원 백필 없음(확정). 배포 즉시 정책 일괄 적용: 기존 성공 스캔 3회 이상·해금 안 된 회원은 잠기고, 새 리뷰 작성으로 해금.
- **Rationale**: 사용자 확정(백필 쿼리 금지). additive DEFAULT 컬럼이라 블루/그린 안전.

## R6. 범위 밖(별도 태스크)

- 재잠금 배치(리뷰 0건 해금 회원 주기 검사·`scan_unlocked=false` 회수) — 이 기능이 남긴 플래그를 소비하는 후속 태스크. 잔여 횟수 조회 API·기기/IP 단위 방어·크레딧 결제도 범위 밖.
