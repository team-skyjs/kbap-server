# Implementation Plan: 회원 스캔 이용 정책 — 무료 3회·리뷰 작성 시 해금

**Branch**: `kb-345-scan-credit-limit` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-345-scan-credit-limit/spec.md`

## Summary

`member.scan_unlocked`(BOOLEAN, DEFAULT false, 백필 없음) 플래그를 신설하고, `ScanService.scan` 공통 본체 초입(비용 발생 전)에서 `scanUnlocked || scanCount < 3` 판정으로 v1·v2 를 공통 제한한다. 초과·미해금 시 신규 **403 SCAN-004** 로 거절(리뷰 작성 유도 분기). 해금은 `increaseReviewCount` 원자 UPDATE 에 `scanUnlocked = true` 를 동승시켜 리뷰 작성 즉시·원자 반영. 카운트는 현행(성공 스캔 후)·실패 미소모 유지. 재잠금 배치는 별도 태스크 — 이번 범위는 그 배치가 소비할 플래그 저장까지.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택)

**Primary Dependencies**: Spring Boot 4.1 (web·data-jpa), Flyway — 신규 의존 없음

**Storage**: MySQL `member` — BOOLEAN 컬럼 1개 추가(DEFAULT false, 백필 없음)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers (ScanControllerTest 시나리오 추가)

**Target Platform**: `:api`(판정·에러) + `:common`(엔티티·리포지토리 UPDATE·에러코드)

**Project Type**: web-service — 기존 스캔 흐름에 게이트 추가

**Performance Goals**: 거절 시 외부 호출·이력 0건(SC-001) — 판정은 이미 로드된 member 로 무추가비용

**Constraints**: v1·v2 공통 판정 · 성공만 카운트(현행 유지) · 동시 초과 1~2회 감수(격리수준 무조정) · 백필 금지

**Scale/Scope**: 마이그레이션 1 + 파일 5개 수정 + 테스트 — 소형

## Constitution Check

*GATE: 통과(위반 없음). Phase 1 설계 후 재평가 — 동일.*

- **I. Test-First**: 4회째 403·실패 미소모·해금 즉시 시나리오 Red 선작성 후 구현. 통과.
- **II. Bounded Contexts**: member 도메인(플래그·UPDATE)과 api scan 기능(판정) — 기존 방향 그대로(scan→member 는 이미 소비 중). 통과.
- **III. Layered Dependency Direction**: api → common 유지. 통과.
- **IV. Persistence Ownership**: 컬럼은 member 도메인 소유, 해금은 리포지토리 원자 UPDATE(카운트 UPDATE 와 동일 패턴). 판정식은 Member 도메인 메서드(`isScanAllowed()`)로 엔티티가 소유. 통과.
- **V. Domain Content Language Policy**: 무관(숫자·불리언 정책). 에러 메시지는 code 분기 규약 그대로. 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-345-scan-credit-limit/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 6건(상태 모델·해금 동승·판정 위치/에러·카운트·소급·범위 밖)
├── data-model.md        # Phase 1 — scan_unlocked 컬럼·판정식·UPDATE 변경
├── quickstart.md        # Phase 1 — 수동 검증
├── contracts/
│   └── scan-limit.md    # 403 SCAN-004 계약·허용 규칙
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/V<timestamp>__member_scan_unlocked.sql  # ALTER TABLE member ADD COLUMN
common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt              # SCAN_LIMIT_EXCEEDED("SCAN-004", 403)
common/src/main/kotlin/com/kbap/common/domain/member/model/Member.kt        # scanUnlocked 필드 + isScanAllowed(limit) 도메인 메서드
common/src/main/kotlin/com/kbap/common/domain/member/MemberJpaRepository.kt # increaseReviewCount 에 scanUnlocked=true 동승
api/src/main/kotlin/com/kbap/api/scan/ScanService.kt                        # scan() 초입 판정 — 미허용 403 SCAN-004
api/src/main/kotlin/com/kbap/api/scan/ScanApi.kt·ScanV2Api.kt               # swagger 403 문서
api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt                 # 제한·해금 시나리오
```

**Structure Decision**: 판정식은 `Member.isScanAllowed()` 도메인 메서드로 엔티티가 소유(무료 한도 상수 포함), ScanService 는 호출만 — 정책 숫자가 서비스에 흩어지지 않는다. 해금은 기존 원자 UPDATE 동승이라 리뷰 쪽 코드 변경이 0줄이다.

## 구현 노트 (Phase 1 설계 확정)

- (재개정 2026-08-19) 게이트 = `MemberJpaRepository.reserveScan` 조건부 원자 UPDATE(비전 호출 전 선점, 0행 → 403) + 실패 경로 `releaseScan` 보상 — research R4 참조. `Member.isScanAllowed()` 는 게이트가 UPDATE 로 이동하며 제거.
- `FREE_SCAN_LIMIT = 3` 은 `Member` companion 상수.
- 동시성은 치명 경로로 승격(무제한 유료 스캔 노출) — 원자 선점의 동시 테스트(`MemberScanReservationTest`)를 둔다.
- 리뷰 삭제(`decreaseReviewCount`)는 건드리지 않는다 — 재잠금은 별도 태스크(배치)가 `scanUnlocked && reviewCount = 0` 회원을 회수.
- OpenAPI 스냅샷 변경(403 응답 추가) 시 갱신 절차대로 재생성.

## Complexity Tracking

> 위반 없음 — 해당 없음.
