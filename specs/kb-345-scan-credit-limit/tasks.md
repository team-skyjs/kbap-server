# Tasks: 회원 스캔 이용 정책 — 무료 3회·리뷰 작성 시 해금

**Input**: Design documents from `/specs/kb-345-scan-credit-limit/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-limit.md

**Tests**: Test-First (헌법 원칙 I) — 스토리마다 실패 테스트(Red) 선작성 후 구현(Green).

**Organization**: Foundational = 스키마·에러코드·도메인 판정식(두 스토리 공통 전제, ddl-auto=validate 라 엔티티·마이그레이션 동행). US1(제한·403) → US2(리뷰 해금) 순서.

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: `scan_unlocked` 컬럼 + SCAN-004 + `Member.isScanAllowed()` 판정식.

- [x] T001 [P] Flyway 마이그레이션 — `api/src/main/resources/db/migration/V<생성시각>__member_scan_unlocked.sql`: `ALTER TABLE member ADD COLUMN scan_unlocked tinyint(1) NOT NULL DEFAULT 0` (파일명 timestamp 포맷, 백필 없음)
- [x] T002 [P] 에러 코드 추가 — `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`: `SCAN_LIMIT_EXCEEDED("SCAN-004", 403, "무료 스캔 횟수를 모두 사용했습니다. 리뷰를 작성하면 무제한으로 이용할 수 있어요")`
- [x] T003 **Red(도메인)**: `common/src/test/kotlin/com/kbap/common/domain/member/model/MemberTest.kt` 에 `isScanAllowed` 시나리오 추가 — (1) scanCount 0·2 → true, (2) scanCount 3·10 + 미해금 → false(경계 3 포함), (3) scanCount 10 + 해금 → true. 실행해 **실패(Red) 확인**(컴파일 에러도 Red)
- [x] T004 도메인 확장(Green) — `common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt`: `@Column(name = "scan_unlocked", nullable = false) var scanUnlocked: Boolean = false` 필드, `FREE_SCAN_LIMIT = 3` companion 상수, `fun isScanAllowed(): Boolean = scanUnlocked || scanCount < FREE_SCAN_LIMIT`. `./gradlew :common:test --tests "*MemberTest"` 그린

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` 그린 — 기존 회귀 없음(컬럼 DEFAULT 하위호환).

---

## Phase 2: User Story 1 — 무료 3회를 소진하면 스캔이 잠긴다 (P1)

**Goal**: 소진·미해금 회원의 스캔은 v1·v2 공통으로 403 SCAN-004 — 거절 시 비전 호출·이력·카운트 0.

**Independent Test**: scan_count=3·리뷰 0건 회원 스캔 → 403. 실패 스캔 미소모. 해금 회원은 통과.

- [x] T005 [US1] **Red**: `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 시나리오 추가(scan_count 는 SQL UPDATE 시드) — (1) scan_count=3·미해금 회원 v2 스캔 → 403 SCAN-004, (2) 반복 시도 계속 403 + scan_count 불변·scan_history 0건(비전 호출 전 거절 — vision.program 없이도 403 이어야 함), (3) scan_count=2 회원 비메뉴판 실패(400 SCAN-003) 후 scan_count 불변 → 이어서 성공 스캔 가능(3회째), (4) v1 경로도 scan_count=3 이면 403, (5) scan_count=3 + scan_unlocked=1 → 정상 200. 실행해 **실패(Red) 확인**
- [x] T006 [US1] 판정 구현(Green) — `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt`: `scan()` 의 member 로드 직후 `if (!member.isScanAllowed()) throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)` (이미지 검증·비전 호출 전). `api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt`·`ScanV2Api.kt` 에 403 응답 문서 추가. **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"`

**Checkpoint**: 제한 경로 완결(MVP).

---

## Phase 3: User Story 2 — 리뷰를 작성하면 스캔이 무제한 해금된다 (P1)

**Goal**: 리뷰 작성 즉시 해금(원자) — 삭제해도 해금 유지(재잠금은 별도 태스크 배치).

**Independent Test**: 잠긴 회원 리뷰 작성 → 즉시 스캔 200 + DB scan_unlocked=1.

- [x] T007 [US2] **Red**: `ScanControllerTest.kt`(또는 적절한 기존 클래스) 시나리오 추가 — (1) scan_count=3·잠긴 회원이 리뷰 1건 작성(POST /api/reviews) 직후 스캔 → 200, DB `scan_unlocked=1`, (2) 그 리뷰를 삭제해도 `scan_unlocked=1` 유지·스캔 계속 가능(재잠금은 후속 배치). 실행해 실패 확인
- [x] T008 [US2] 해금 구현(Green) — `common/src/main/kotlin/com/kbap/common/domain/member/MemberJpaRepository.kt`: `increaseReviewCount` UPDATE 에 `m.scanUnlocked = true` 동승(리뷰 쪽 코드 무변경). **Green 확인**: 동일 테스트 클래스 그린

**Checkpoint**: 제한↔해금 왕복 완결.

---

## Phase 4: Polish & Cross-Cutting

- [x] T009 전체 빌드 그린 — `./gradlew build` (OpenAPI 스냅샷 403 추가 반영·ArchUnit·ddl-auto=validate 포함). 필요시 quickstart.md 수동 검증

---

## Dependencies

```text
Foundational: T001 ∥ T002 → T003(Red) → T004(Green) → checkpoint
  → US1: T005(Red) → T006(Green)
  → US2: T007(Red) → T008(Green)   # 판정(US1)이 있어야 해금 효과 검증 가능
  → Polish: T009
```

- [P]: T001∥T002(다른 파일·무의존).

## Implementation Strategy

- **MVP = Foundational + US1**: 제한만으로도 정책 발효 — US2 는 UPDATE 한 줄.
- 판정~카운트 사이 동시 초과는 감수(헌법) — 동시성 테스트 작성하지 않는다.
- 재잠금 배치는 별도 태스크(Jira 미등록 상태) — 이 기능 머지 후 등록 권장.
- 커밋 단위: 단일 feature 커밋.

---

## 재작업 (2026-08-19 — Redis 예약 슬롯 교체, research R4 재재개정)

- [x] R001 seam `common/src/main/kotlin/com/kbap/common/port/scan/ScanReservationStore.kt` — reserve(memberId, requestId, confirmedCount, limit)/release + 결과 enum(RESERVED/LIMIT_EXCEEDED/DUPLICATE_REQUEST)
- [x] R002 어댑터 `api/src/main/kotlin/com/kbap/api/infra/redis/RedisScanReservationStore.kt` — ZSET `scan:reservations:{memberId}` + Lua(만료 정리→중복→ZCARD 한도→ZADD+PEXPIRE), TTL `kbap.scan.reservation-ttl-seconds`(기본 300)
- [x] R003 `ScanService.scan` 재배선 — 해금 회원은 예약 미경유, 예약→doScan→DB 커밋→release 순서(commit-before-release), 실패 시 release 만
- [x] R004 2차안 롤백 — `MemberJpaRepository.reserveScan/releaseScan` 제거, `increaseScanCount` 복원, `MemberScanReservationTest` 삭제
- [x] R005 멱등 키 — `Idempotency-Key` 요청 헤더(선택, 두 컨트롤러 `@RequestHeader`), 중복 409 `SCAN-005`(ErrorCode·swagger)
- [x] R006 테스트 — `RedisScanReservationStoreTest`(동시 5스레드 정확성·중복·만료 회수·멱등 release), ScanControllerTest Redis 컨테이너 추가 + 처리 중 Idempotency-Key 중복 409 시나리오
- [x] R007 LLM 서버 장애 코드 분리 — port 예외 `MenuBoardVisionUnavailableException`, 어댑터가 `TransientAiException`/`ResourceAccessException` 번역, 503 `SCAN-006`(재시도 유도 모달 분기), ScanControllerTest 장애 시 횟수 미소모 시나리오
- [x] R008 서버 발급 스캔 티켓 — seam `ScanTicketCodec`(port.scan) + `JwtScanTicketCodec`(api.infra.auth.token, TokenType.SCAN_TICKET·TTL `kbap.scan.ticket-ttl-seconds`), `POST /api/scans/tickets`(발급 시 isScanAllowed 선검사 403), v2 스캔 `X-Scan-Ticket` 필수(jti=예약 키, 위조·만료·타인 400 SCAN-007), Idempotency-Key 헤더 폐기, 테스트(발급 200/403·티켓 누락 400·위조/타인 SCAN-007·처리 중 중복 409)
- [x] R009 성공 release 를 AFTER_COMMIT 이벤트로 구조화 — `TransactionTemplate`(카운트 증가+`ScanConfirmed` 발행 커밋) + `@TransactionalEventListener` release. commit-before-release 가 코드 배치 관례에서 구조 보장으로 승격
- [x] R010 재잠금 회귀 시나리오 — 해금 후 10회 스캔 회원이 배치 재잠금(scan_unlocked=false)되면 누적 카운트(11)가 한도(3)를 초과한 상태 그대로 발급·v1 스캔 403 잠금 확인(ScanControllerTest)
- [x] R011 퍼사드 분리 — `ScanFacade`(api.scan)가 티켓 발급·verify·예약·보상·AFTER_COMMIT release 오케스트레이션을 소유, `ScanService` 는 스캔 본체(LLM·매칭·이력)와 `@Transactional confirmScan`(카운트+이벤트)·이력 조회만. TransactionTemplate 제거, 컨트롤러 3개는 퍼사드 호출. 미사용 imageUploadService 의존 제거
