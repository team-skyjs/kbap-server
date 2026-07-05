---
description: "Task list — Flyway 점 구분 timestamp 버전 규칙 전환"
---

# Tasks: Flyway 점 구분 timestamp 버전 규칙 전환

**Input**: Design documents from `specs/kb-44-flyway-timestamp-versioning/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, quickstart.md ✅ (contracts/ 없음 — 내부 컨벤션 작업)

**Tests**: ⚠️ **Test-First 해당 없음(정당화된 예외)**. 본 작업은 프로덕션 도메인 코드 변경이 없고(문서 + `application.yml` 1줄), Flyway 동작은 테스트 스위트(H2·flyway off)에서 실행조차 되지 않는다. 따라서 실패 테스트를 먼저 쓸 대상이 없다. 검증은 **로컬 docker MySQL 실측**(quickstart.md, US4)으로 대체한다. 근거: plan.md Constitution Check / Complexity Tracking.

**Organization**: 사용자 스토리별로 그룹화. 단, US1~US3 은 **같은 두 문서 파일**(`CLAUDE.md`·`docs/architecture/meogo-conventions.md`)의 서로 다른 서술 facet 이라, 동일 파일 편집끼리는 순차 실행한다([P] 불가). 파일이 다른 편집(`application.yml`)만 병렬 가능.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일 + 선행 의존 없음 → 병렬 가능
- **[Story]**: US1~US4 (spec.md 매핑)
- 모든 태스크에 정확한 파일 경로 포함

## Path Conventions

- 컨벤션 문서(런타임 가이드): `CLAUDE.md`
- 컨벤션 문서(상세 규범): `docs/architecture/meogo-conventions.md`
- 설정: `app/api/src/main/resources/application.yml`
- 기존 마이그레이션(읽기 전용): `app/api/src/main/resources/db/migration/`

---

## Phase 1: Setup

**Purpose**: 편집 지점 확인

- [ ] T001 삽입 위치 확인 — `CLAUDE.md` 컨벤션 섹션에서 Flyway/JPA 규약 인접 라인(예: "엔티티 컬럼 길이는 Flyway 마이그레이션과 일치시킨다" 근처)과 `docs/architecture/meogo-conventions.md` 의 DB/마이그레이션 서술 영역을 찾아 신규 "Flyway 마이그레이션 버전 규칙" 블록/섹션을 넣을 위치를 정한다.

---

## Phase 2: Foundational

**Purpose**: 없음 — 공유 blocking 코드/스키마가 없다. 이 작업엔 별도 foundational 선행물이 없으며, 각 스토리는 Setup 후 바로 진행한다. (out-of-order 설정은 spec 이 US3 로 귀속시켜 Phase 5 에 둔다.)

**Checkpoint**: Setup(T001) 완료 즉시 스토리 작업 가능.

---

## Phase 3: User Story 1 - 신규 마이그레이션을 점 구분 timestamp 로 작성 (Priority: P1) 🎯 MVP

**Goal**: 개발자가 문서만 보고 신규 마이그레이션 파일명을 `Vyyyy.MM.dd.HH.mm.ss__description.sql`(생성 시각 초 단위, zero-pad) 로 지을 수 있게 한다.

**Independent Test**: 문서만 읽고 규칙에 맞는 신규 파일명 1건을 만들 수 있으면 통과 (spec SC-001).

### Implementation for User Story 1

- [ ] T002 [US1] `CLAUDE.md` 컨벤션 섹션에 "Flyway 마이그레이션 버전 규칙(고정)" 짧은 규칙 블록 추가 — 신규는 `Vyyyy.MM.dd.HH.mm.ss__description.sql`(점 구분, 초 단위, 각 파트 두 자리 zero-pad), 버전은 **생성 시점 로컬 현재 시각** 기준, 올바른 예시(`V2026.07.05.14.30.12__add_review_table.sql`) 1건 포함. (data-model.md §1 문법 근거)
- [ ] T003 [US1] `docs/architecture/meogo-conventions.md` 에 "Flyway 마이그레이션 버전 규칙" 섹션 신설 — 파일명 구성요소(Prefix/Version/Separator/Description/Suffix), 점 구분 timestamp 포맷과 **공식 문서 근거**(유효 예시 `2013.01.15.11.35.56`, *"versions are sorted numerically"*) 서술. (research.md R1·R2)

**Checkpoint**: 신규 파일명 규칙이 두 문서에 존재 — US1 독립 검증 가능.

---

## Phase 4: User Story 2 - 기존 정수 이력과의 공존·순서 이해 (Priority: P1)

**Goal**: 기존 `V1`~`Vn` 정수 파일을 수정·리네임하면 안 됨과, 정수·timestamp 가 숫자 정렬로 공존하며 뒤늦은 머지가 out-of-order 임을 문서로 이해시킨다.

**Independent Test**: 문서를 읽고 (a) 기존 파일 수정 가부, (b) `V10` < `V2026.07.05...` 공존 이유, (c) out-of-order 발생 시점을 설명할 수 있으면 통과.

### Implementation for User Story 2

- [ ] T004 [US2] `docs/architecture/meogo-conventions.md` 의 T003 섹션에 정수↔timestamp **공존·숫자 정렬**(`10 < 2026.07.05.14.30.12`, 기존 이력이 항상 앞섬)과 **out-of-order 발생 메커니즘**(생성 시각 기반 → 먼저 만들고 늦게 머지 시 과거 버전이 뒤늦게 적용, `installed_rank` 가 실행 순서 보존) 서술 추가. (research.md R2·R3, data-model.md §1 정렬/상태전이)
- [ ] T005 [US2] `CLAUDE.md` T002 규칙 블록에 **금지 사례** 명시 — (1) 신규에 정수 `V11` 사용 금지, (2) 기존 `V1`~`V10` 파일 수정·리네임 금지(checksum/history 파손), 및 "정수 이력과 timestamp 공존" 한 줄. (spec SC-002)

**Checkpoint**: US1 + US2 문서가 공존·순서·금지 경계를 모두 담음.

---

## Phase 5: User Story 3 - out-of-order 허용 설정과 마이그레이션 독립성 원칙 (Priority: P1)

**Goal**: `out-of-order` 를 켜 과거 timestamp 뒤늦은 머지가 배포를 막지 않게 하고, "마이그레이션 순서-독립 작성" 원칙을 문서로 강제한다.

**Independent Test**: 설정이 반영돼 과거 timestamp 가 부팅을 막지 않고, 문서에 순서-독립 원칙과 근거가 있으면 통과 (spec SC-005).

### Implementation for User Story 3

- [ ] T006 [P] [US3] `app/api/src/main/resources/application.yml` 의 `spring:` 아래에 `flyway.out-of-order: true` 추가(베이스 yml — 전 프로필 상속). 기존 `spring.ai...` 등 다른 키를 건드리지 않는다. (research.md R5, data-model.md §2)
- [ ] T007 [US3] `docs/architecture/meogo-conventions.md` 의 T003 섹션에 **out-of-order 허용 근거**(공식 문서 경고 *"rerunning ... might produce different results"* → 설정으로 배포 차단 해제)와 **마이그레이션 순서-독립 원칙**(다른 미적용 마이그레이션의 실행 순서에 의존 금지, 그래야 out-of-order 시 결과 불변) 서술 추가. (research.md R3, data-model.md INV-4)
- [ ] T008 [US3] `CLAUDE.md` T002 규칙 블록에 순서-독립 원칙 한 줄 + `out-of-order=true` 활성 사실 + 금지 사례 (3) "순서 의존 마이그레이션 작성 금지" 추가.

**Checkpoint**: 설정 + 문서 원칙이 한 쌍으로 완성 — US1~US3(P1) 모두 완료 = 규칙 도입 완결.

---

## Phase 6: User Story 4 - 로컬 MySQL 정렬·out-of-order·checksum 검증 (Priority: P2)

**Goal**: 점 구분 timestamp + out-of-order 가 로컬 docker MySQL 에서 실제로 동작하고 기존 checksum 을 깨지 않음을 실측한다.

**Independent Test**: quickstart.md 절차 완주 시 정상 적용·out-of-order 적용·checksum 통과 확인 (spec SC-004).

**Prerequisite**: T006(out-of-order 설정) 완료 필요.

### Implementation for User Story 4

- [ ] T009 [US4] `specs/kb-44-flyway-timestamp-versioning/quickstart.md` 절차대로 로컬 docker MySQL(`meogo-mysql`, `SPRING_PROFILES_ACTIVE=local`)에서 검증 수행 — (a) 신규 timestamp 정상 적용, (b) 과거 timestamp **out-of-order 정상 적용**(부팅 안 막힘), (c) 기존 `V1`~`V10` checksum validate 통과. probe 마이그레이션/테이블은 §5 대로 원상 복구. 결과를 요약해 기록. (앱이 IntelliJ 로 8080 점유 중일 수 있으므로 broad `pkill` 금지)

**Checkpoint**: 규칙이 실동작으로 입증됨.

---

## Phase 7: Polish & Cross-Cutting

**Purpose**: 마무리 점검

- [ ] T010 [P] 자기검증 — `git diff --stat app/api/src/main/resources/db/migration/` 로 기존 `V1`~`V10` 파일이 **무변경**임을 확인(SC-003), 문서에 올바른 예시 1건 + 금지 사례 3건 존재 확인(SC-002).
- [ ] T011 커밋 — 문서(CLAUDE.md·meogo-conventions.md)와 설정(application.yml) 변경을 논리 단위로 커밋(예: `docs(flyway): 마이그레이션 버전 timestamp 규칙 + out-of-order 도입`). 커밋 메시지에 "왜"(병렬 머지 충돌 제거·공식 문서 근거) 기록.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(T001)**: 즉시 시작.
- **Foundational**: 없음.
- **US1(T002–T003) → US2(T004–T005) → US3(T006–T008)**: 셋 다 P1. **같은 두 문서 파일을 공유**하므로 순차 진행 권장(동일 파일 편집 충돌 방지). US3 의 T006(application.yml)만 문서 편집과 병렬 가능.
- **US4(T009)**: **T006 완료 후** 실행(out-of-order 설정 필요). P2.
- **Polish(T010–T011)**: 모든 변경 완료 후.

### 동일 파일 순서 제약

- `CLAUDE.md`: T002 → T005 → T008 (순차, [P] 불가)
- `docs/architecture/meogo-conventions.md`: T003 → T004 → T007 (순차, [P] 불가)
- `app/api/src/main/resources/application.yml`: T006 (독립, [P])

### Parallel Opportunities

- T006(application.yml)은 문서 편집 태스크와 병렬 가능.
- T010 은 다른 검토와 병렬 가능.
- 그 외 문서 편집은 파일 공유로 순차.

---

## Implementation Strategy

### MVP (US1)

1. T001 Setup → T002·T003(US1) 완료 → 신규 timestamp 파일명 규칙이 문서에 존재.
2. **STOP & VALIDATE**: 문서만 보고 규칙에 맞는 파일명을 지을 수 있는지 확인(SC-001).

### Incremental (권장 순서)

1. US1 → 파일명 규칙(MVP)
2. US2 → 공존·금지 경계
3. US3 → out-of-order 설정 + 순서-독립 원칙 (규칙 완결)
4. US4 → 로컬 MySQL 실측 검증
5. Polish → 무변경 확인 + 커밋

> 사실상 순차 진행이 자연스럽다(공유 문서 파일 + 소규모). "빠르게" 목표에 맞춰 US1~US3 문서 편집을 한 세션에 몰아 처리하고, T006 설정 → T009 검증 순으로 마무리하면 된다.

---

## Notes

- [P] = 다른 파일 + 무의존. 본 작업은 공유 문서 파일이 많아 [P] 기회가 적다(T006·T010 정도).
- 기존 `V1`~`V10` 파일은 **절대** 수정·리네임하지 않는다(T010 에서 무변경 검증).
- Kotlin 코드 변경이 없으므로 주석 금지 규약·모듈 경계·응답/경로 규약은 이 작업과 무관.
- 커밋은 논리 단위로(문서/설정), 메시지에 근거를 남긴다.
