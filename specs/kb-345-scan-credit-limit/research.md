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

## R4. 카운트·동시성 — (재재개정 2026-08-19: Redis 예약 슬롯)

- **초안 폐기**: "판정(read) 후 성공 시 카운트" 구조는 판정~카운트 사이에 LLM 호출(수 초)이 껴서, 동시 버스트 N개가 전부 게이트를 통과해 **무제한 유료 스캔**이 가능하다는 리뷰 지적으로 폐기.
- **2차안(조건부 원자 UPDATE 선점 + 보상 UPDATE) 폐기**: MySQL 행 잠금으로 분산 안전은 성립하나, (1) 보상 전 크래시 시 무료 1회 영구 유실, (2) 처리 중 선점분이 `scan_count`(랭킹 데이터)에 순간 오염, (3) 확정 스캔만 담아야 할 컬럼에 in-flight 상태가 섞이는 의미 훼손 — Redis 예약안이 세 가지를 모두 해소해 교체.
- **Decision (최종)**: **MySQL = Source of Truth**(`scan_count` 는 확정 성공 스캔만), **Redis ZSET = per-request in-flight 예약**.
  - 키: `scan:reservations:{memberId}`, member=`requestId`(클라이언트 UUID, 미제공 시 서버 생성), score=만료 timestamp.
  - 예약은 **Lua 스크립트 하나로 원자 실행**: 만료분 ZREMRANGEBYSCORE 정리 → requestId 중복(ZSCORE) 검사 → `dbScanCount + ZCARD >= 3` 검사 → ZADD + PEXPIRE. 결과 1=예약/2=중복(409 SCAN-005)/0=한도(403 SCAN-004).
  - 성공 순서 엄수: LLM 성공 → **DB scanCount+1 커밋** → 그 후 Redis 예약 제거(commit-before-release — 역순이면 슬롯 반납~커밋 사이 창에 한도 초과 통과 가능). 순서는 코드 배치가 아니라 **구조로 보장**한다 — `TransactionTemplate` 커밋 안에서 `ScanConfirmed` 이벤트를 발행하고 `@TransactionalEventListener(AFTER_COMMIT)` 가 release 를 수행(2026-08-20). 실패 경로 release 는 catch 에서 즉시 실행(트랜잭션 없음 — 이벤트 미적용).
  - LLM 실패(비메뉴판 포함): scanCount 미증가, 예약만 제거 — 기회 보존(FR-004).
  - 크래시·release 실패: 예약 TTL(기본 300초, `SCAN_RESERVATION_TTL_SECONDS`)로 자생 회수 — 무료 횟수 영구 유실 없음.
  - 해금 회원(`scan_unlocked`)은 예약 로직을 타지 않는다(카운트 증가만 수행 — 랭킹 유지).
- **경계**: seam `common.port.scan.ScanReservationStore`(Spring-free), 구현 `api.infra.redis.RedisScanReservationStore`(ADR-0018 api 전용 어댑터 위치). LLM 호출은 어떤 DB 트랜잭션·잠금 밖, 앱 로컬 락 없음(EC2 2대).
- **Alternatives considered**: 비관 잠금 직렬화 — 외부 호출을 잠금 안에 가둠, 기각. 조건부 원자 UPDATE 선점 — 위 폐기 사유. 크레딧 원장 테이블 — 고정 한도 3회에 과설계.

## R4-1. 멱등 키 조작 방어 — 서버 발급 스캔 티켓 (2026-08-20 추가)

- **Decision**: 클라이언트 생성 멱등 키를 폐기하고 **서버 서명 티켓**(`POST /api/scans/tickets` → jjwt HMAC, claims: memberId·jti·exp 300초)으로 대체. v2 스캔은 `X-Scan-Ticket` 헤더 필수, jti 가 Redis 예약 키. 발급 시 `Member.isScanAllowed()` 선검사로 소진 회원을 **업로드 전에** 403 차단.
- **Rationale**: 클라이언트 UUID 조작으로 한도가 뚫리진 않지만(판정은 확정 횟수+예약 수), 발급 단계가 있으면 (1) 업로드 비용 없는 조기 차단, (2) 향후 발급 rate limit·사전 조건(캡차 등)의 단일 관문, (3) 멱등 키 품질의 서버 보장을 얻는다. seam `common.port.scan.ScanTicketCodec`, 구현 `api.infra.auth.token.JwtScanTicketCodec`(기존 jwt secret 재사용, TokenType.SCAN_TICKET).
- **범위 밖(후속)**: 발급 rate limit Redis 카운팅(예: 5분 5회). 티켓의 완료-후 재사용 차단(완료 이력 미보관 — in-flight 중복만 차단).
- **Alternatives considered**: 예약 경로 인라인 rate limit(발급 API 없이) — 조기 차단·관문 확장성이 없어 사용자 선택으로 기각. Redis 저장형 티켓 — stateless 서명으로 충분.

## R5. 소급·마이그레이션

- **Decision**: `DEFAULT false` 단순 컬럼 추가 — 기존 회원 백필 없음(확정). 배포 즉시 정책 일괄 적용: 기존 성공 스캔 3회 이상·해금 안 된 회원은 잠기고, 새 리뷰 작성으로 해금.
- **Rationale**: 사용자 확정(백필 쿼리 금지). additive DEFAULT 컬럼이라 블루/그린 안전.

## R6. 범위 밖(별도 태스크)

- 재잠금 배치(리뷰 0건 해금 회원 주기 검사·`scan_unlocked=false` 회수) — 이 기능이 남긴 플래그를 소비하는 후속 태스크. 잔여 횟수 조회 API·기기/IP 단위 방어·크레딧 결제도 범위 밖.
