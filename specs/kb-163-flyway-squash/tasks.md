# Tasks: Flyway 마이그레이션 스쿼시 — 스키마·시드 분리 및 프로필별 적용

**Input**: Design documents from `specs/kb-163-flyway-squash/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: 헌법 원칙 I(Test-First) — 리소스 가드 테스트가 Red 진입점이다. 스키마 정합은 기존 통합 스위트(Testcontainers + `ddl-auto=validate`)가 커버한다.

**Organization**: 사용자 스토리별 페이즈. **주의 — 실행 순서는 US1 → US3 → US2** 다: US2(기존 DB 재기준선)의 baseline-version 이 US3 산출물(데모 시드 파일의 버전)에 의존한다(research R5).

## Phase 1: Foundational (모든 스토리의 선행 — 최종 상태 도출)

- [X] T001 docker MySQL(kbap-old, 포트 3307)에 기존 22개 마이그레이션을 버전 순 적용하고 스키마 덤프(`--no-data`)·데모 데이터 덤프(`--no-create-info`, food·food_avoidance_substance)를 스크래치 디렉터리에 확보 — quickstart.md §1 전반부 절차

**Checkpoint**: old-schema.sql(권위 스키마)·demo-data.sql(데모 최종 행) 확보 — US1·US3 의 원본

---

## Phase 2: User Story 1 — 신규 환경은 스키마 + 마스터만으로 초기화 (P1) 🎯 MVP

**Goal**: `db/migration` = 스키마 전용 init 1개 + 마스터 시드(기피물질 81종) 2파일 체제. 데모 데이터가 prod 적용 위치에 존재하지 않음을 테스트가 영구 가드.

**Independent Test**: `:app:api:test` — `MigrationLayoutTest`(신규)·`AvoidanceCatalogSeedSyncTest`(경로 갱신) Green + 신구 스키마 diff = 0.

- [X] T002 [US1] **[Red]** `MigrationLayoutTest` 작성(BehaviorSpec) — (a) `db/migration` 에 SQL 이 정확히 2개(`__init_schema`·`__seed_avoidance_catalog`), (b) init 에 `INSERT` 부재, (c) 마스터 시드의 `avoidance_substance` 행 81건, (d) `db/migration` 전체에 `INSERT INTO food` 부재 — `app/api/src/test/kotlin/com/kbap/app/api/migration/MigrationLayoutTest.kt`. 실행해 **Red 확인**
- [X] T003 [US1] old-schema.sql 덤프를 정리해 스키마 전용 init 작성(테이블 7종, `flyway_schema_history` 제외, INSERT 0건) — `app/api/src/main/resources/db/migration/V<ts>__init_schema.sql` (버전 = 생성 시각 timestamp, KB-44)
- [X] T004 [P] [US1] 마스터 시드 작성 — 구 `V2026.07.02.01.09.09__create_avoidance_catalog_and_mapping.sql` 의 `avoidance_substance` INSERT 81행만 추출(폐기된 category·ingredient 매핑 제외, 시드-동기화 테스트가 파싱하는 11필드 행 형식 유지) — `app/api/src/main/resources/db/migration/V<ts>__seed_avoidance_catalog.sql` (init 보다 뒤 버전)
- [X] T005 [US1] 기존 마이그레이션 22개 파일 삭제 + `AvoidanceCatalogSeedSyncTest.seedResourcePath` 를 새 마스터 시드 경로로 갱신 — `app/api/src/test/kotlin/com/kbap/app/api/avoidance/AvoidanceCatalogSeedSyncTest.kt`
- [X] T006 [P] [US1] 베이스 yml 에 안전 기본값 명시: `spring.flyway.locations: classpath:db/migration`(+ 분리 사유 주석) — `app/api/src/main/resources/application.yml`
- [X] T007 [US1] 검증: 빈 docker MySQL(kbap-new, 포트 3308)에 새 init 적용 → kbap-old 와 스키마 덤프 diff = 0(AUTO_INCREMENT 정규화) — quickstart.md §1 후반부
- [X] T008 [US1] **[Green]** `./gradlew :app:api:test` — MigrationLayoutTest·AvoidanceCatalogSeedSyncTest 통과 확인

**Checkpoint**: prod 상당 환경(베이스 locations)이 스키마+마스터만으로 초기화되는 MVP 완성

---

## Phase 3: User Story 3 — 로컬·dev 는 데모 시드 포함 즉시 재구성 (P3) — US2 선행 의존

**Goal**: 데모 시드(`db/seed`) 분리 + local·dev 프로필만 적용. (P3 이지만 US2 의 baseline-version 이 이 파일 버전에 의존해 먼저 수행)

**Independent Test**: 빈 docker DB + local 프로필 부팅 → 테이블 7종 + 마스터 81 + 데모 음식 10건.

- [X] T009 [US3] **[Red]** `MigrationLayoutTest` 에 db/seed 가드 추가 — `db/seed` 에 데모 시드 1개 존재, `INSERT INTO food` 10행·`food_avoidance_substance` INSERT 존재 — `app/api/src/test/kotlin/com/kbap/app/api/migration/MigrationLayoutTest.kt`. Red 확인
- [X] T010 [US3] demo-data.sql 덤프를 정리해 데모 시드 작성(음식 10건 최종 상태 + 매핑) — `app/api/src/main/resources/db/seed/V<ts>__seed_demo_food_data.sql` (마스터보다 뒤 버전 — 새 파일 3개 중 최고 버전이어야 함, research R5)
- [X] T011 [P] [US3] local·dev 프로필에 `spring.flyway.locations: classpath:db/migration,classpath:db/seed` 오버라이드 — `app/api/src/main/resources/application-local.yml`, `app/api/src/main/resources/application-dev.yml`
- [X] T012 [US3] **[Green]** `:app:api:test` Green + 빈 docker DB 에 local 프로필 부팅(DB_URL 오버라이드)으로 마스터 81·데모 10 적용 확인 — quickstart.md §3

**Checkpoint**: 프로필별 적용 범위 완성 — prod=스키마+마스터 / local·dev=+데모

---

## Phase 4: User Story 2 — 기존 DB 데이터 보존 전환 (P2)

**Goal**: 홈서버 dev DB 를 drop 없이 재기준선으로 전환하는 런북을 리허설로 검증. 회원·음식 데이터 손실 0.

**Independent Test**: 구 22개가 적용된 docker DB 에 더미 데이터를 넣고 런북 수행 → 전후 행 수 100% 일치 + 정상 부팅.

- [X] T013 [US2] 재기준선 리허설: kbap-old(구 22개 적용)에 더미 회원·북마크·스캔이력 INSERT → 행 수 기록 → `DROP TABLE flyway_schema_history` → `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` + `SPRING_FLYWAY_BASELINE_VERSION=<데모 시드 버전>` 으로 local 프로필 1회 부팅(DB_URL=docker) → 행 수 전후 일치·데모 시드 미재적용·정상 부팅 확인 — quickstart.md §4 절차 그대로
- [X] T014 [US2] quickstart.md §4 런북에 확정 버전 번호·리허설 결과 반영(홈서버 실제 전환은 머지 후 운영 작업) — `specs/kb-163-flyway-squash/quickstart.md`

**Checkpoint**: FR-005(데이터 손실 0) 절차가 리허설로 입증됨

---

## Phase 5: Polish & 전체 검증

- [X] T015 `./gradlew test` 전체 스위트(전 모듈, MySQL Testcontainers 통합 포함) — 데모 시드 행을 암묵 전제한 테스트가 깨지면 자체 시드(FoodTestSeed 등)로 수정
- [X] T016 docker 컨테이너(kbap-old·kbap-new) 정리 + 작업 단위 커밋 정리(스펙 문서·SQL·yml·테스트)

---

## Dependencies & Execution Order

```text
T001 (덤프)
 └─ US1: T002(Red) → T003·T004[P]·T006[P] → T005 → T007 → T008(Green)
     └─ US3: T009(Red) → T010·T011[P] → T012(Green)
         └─ US2: T013 → T014          ← 데모 시드 버전에 의존(research R5)
             └─ Polish: T015 → T016
```

- **[P] 병렬 기회**: T003 ∥ T004 ∥ T006 (서로 다른 파일), T010 ∥ T011.
- **MVP**: Phase 2(US1)까지 — prod 안전 초기화가 곧 이 작업의 존재 이유.
- **스토리 독립성 예외**: US2 리허설은 US1·US3 산출물(파일 버전)에 의존한다 — 가치 우선순위(P2)와 실행 순서가 다른 이유를 본문에 명시했다.

## Implementation Strategy

US1 완성 시점에 이미 "prod 데모 유입 금지"가 테스트로 가드된다. US3 는 파일 1개 + yml 2줄, US2 는 코드가 아니라 리허설·런북이다. 전 과정에서 도메인 코드는 0줄 변경 — 깨지는 테스트가 있다면 그 테스트의 데모 시드 의존이 원인이며, 자체 시드로 고치는 것이 올바른 방향이다(research R4).
