---

description: "Task list — MySQL Testcontainers 도입 (KB-46)"
---

# Tasks: MySQL Testcontainers 도입 (프로덕션-동등 통합 테스트)

**Input**: Design documents from `specs/kb-46-mysql-testcontainers/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [quickstart.md](./quickstart.md)

**Tests**: 이 기능의 산출물 자체가 통합 테스트 인프라다. Test-First(헌법 I)는 **US1 의 마이그레이션-검증 테스트를 먼저 작성해 Red 확인 → 컨테이너/Flyway 도입으로 Green** 으로 집행한다. 기존 테스트 전환분은 **회귀 게이트**(엔진 교체 후에도 green 유지) 역할이다.

**Organization**: 스펙의 사용자 스토리(P1→P2→P3)별 phase. 운영 소스(`src/main`)는 전 모듈 무변경 — 빌드·테스트 소스·테스트 리소스만 변경.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1/US2/US3 (Setup·Foundational·Polish 는 라벨 없음)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Testcontainers 의존성·빌드 배선

- [x] T001 Add Testcontainers 라이브러리 좌표 to `gradle/libs.versions.toml` — `testcontainers-mysql`(`org.testcontainers:mysql`), `spring-boot-testcontainers`(`org.springframework.boot:spring-boot-testcontainers`). 버전은 Spring Boot 4.1 BOM 이 관리하므로 카탈로그에 버전 미기입(기존 `# Test` 관례 준수)
- [x] T002 Apply `java-test-fixtures` plugin + testFixtures 의존 to `infra/persistence/build.gradle.kts` — `testFixturesImplementation`(spring-boot-testcontainers, testcontainers-mysql, spring-boot-starter-test, kotest-extensions-spring). (T001 필요)

**Checkpoint**: 컨테이너 라이브러리와 testFixtures 소스셋이 준비됨

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 DB-backed 테스트가 공유할 컨테이너 설정 — **완료 전 어떤 스토리도 시작 불가**

- [x] T003 Create `infra/persistence/src/testFixtures/kotlin/com/meogo/infra/persistence/testsupport/MySqlContainerConfig.kt` — `@TestConfiguration(proxyBeanMethods=false)` 에 `@Bean @ServiceConnection fun mysqlContainer() = MySQLContainer(DockerImageName.parse("mysql:8.4"))`. 이미지 태그 상수를 이 파일에 **단일 출처**로 둠(FR-009)
- [x] T004 [P] Create `infra/persistence/src/testFixtures/kotlin/com/meogo/infra/persistence/testsupport/MySqlIntegrationSpec.kt` — 공통 Kotest 베이스(`abstract class MySqlIntegrationSpec(body: BehaviorSpec.() -> Unit) : BehaviorSpec(body)` + `override fun extensions() = listOf(SpringExtension)` + `@Import(MySqlContainerConfig::class)`), persistence 어댑터 테스트가 상속
- [x] T005 Wire `app/api/build.gradle.kts` — `testImplementation(testFixtures(project(":infra:persistence")))` 추가(컨테이너 설정 소비, persistence↛api 라 순환 없음)

**Checkpoint**: `MySqlContainerConfig`·`MySqlIntegrationSpec` 를 두 모듈 테스트가 사용 가능

---

## Phase 3: User Story 1 - 마이그레이션 검증 (Priority: P1) 🎯 MVP

**Goal**: `app:api` 테스트에서 Flyway ON + `ddl-auto=validate` 로 전환해, 운영 Flyway 마이그레이션 전체가 MySQL 8.4 컨테이너에 실제 적용·검증되게 한다(현재 사각지대 제거).

**Independent Test**: `./gradlew :app:api:test --tests "*MigrationValidationTest"` 실행 시 컨테이너에 마이그레이션이 전부 적용되고, `flyway_schema_history` 및 MySQL 전용 스키마 산출물이 존재해 통과한다.

### Tests for User Story 1 (Test-First: 먼저 작성, 반드시 FAIL 확인) ⚠️

- [x] T006 [US1] Write `app/api/src/test/kotlin/com/meogo/app/api/migration/MigrationValidationTest.kt` — 컨테이너에 Flyway 마이그레이션 전체 적용을 검증: `flyway_schema_history` 의 적용 버전 수/실패 없음, 그리고 마이그레이션이 만든 MySQL 전용 산출물 존재(예: `food.name_translations`·`description_translations` = JSON, `food_avoidance_substance` 테이블, `avoidance_substance.translations` = JSON). **현재 `flyway.enabled=false` 설정에서 실행해 FAIL(Red) 확인**

### Implementation for User Story 1

- [x] T007 [US1] Flip `app/api/src/test/resources/application.yml` — `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate`, H2/`create-drop` 오버라이드 제거 → T006 Green 전환
- [x] T008 [US1] 전체 마이그레이션 체인을 MySQL 8.4 컨테이너에서 **첫 실전 적용**하며 드러나는 결함 수정(리스크 1·2): 마이그레이션 SQL 오류·`validate` 스키마 드리프트(엔티티↔마이그레이션 불일치). 과도한 `validate` 실패 시 `ddl-auto=none` 임시 완화 후 드리프트 항목을 별도 이슈로 기록
- [x] T009 [US1] 시드 픽스처 ↔ Flyway 시드 정합(D-7) in `app/api/src/test/.../food/FoodTestSeed.kt` 및 이를 쓰는 테스트 — 시드 마이그레이션(`seed_food_data`·`seed_real_food_avoidance_substances`)과 중복되는 수기 삽입 제거/조정, 테스트 간 상태 격리(트랜잭션 롤백/정리, FR-006) 보장
- [x] T010 [P] [US1] 기존 `app:api` `@SpringBootTest` 클래스들에 컨테이너 설정 적용 — `@Import(MySqlContainerConfig::class)` 추가(`app/api/src/test/kotlin/com/meogo/app/api/` 의 `food/FoodDetail*`, `scan/MenuScan*`, `config/CorsConfigTest`, `MeogoApiApplicationTests`); 전부 green 유지
- [x] T011 [US1] Remove `testRuntimeOnly(libs.h2)` from `app/api/build.gradle.kts` (FR-008, api 경로)

**Checkpoint**: MVP — 운영 마이그레이션이 테스트에서 실제 검증됨(SC-001). `app:api` DB-backed 테스트 전부 MySQL 컨테이너에서 green

---

## Phase 4: User Story 2 - 영속 계층 실 엔진 검증 (Priority: P2)

**Goal**: `infra:persistence` 어댑터 테스트를 실 MySQL 엔진에서 실행(마이그레이션 없어 스키마는 Hibernate 생성 유지). 기존 어댑터 테스트가 회귀 게이트.

**Independent Test**: `./gradlew :infra:persistence:test` 가 MySQL 컨테이너 위에서 3개 어댑터 테스트를 통과한다(H2 미사용).

### Implementation for User Story 2

- [x] T012 [P] [US2] Convert `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterTest.kt` — `MySqlIntegrationSpec` 상속으로 전환(실 엔진, `ddl-auto=create-drop` 유지), green 확인
- [x] T013 [P] [US2] Convert `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/scan/MenuScanRepositoryAdapterTest.kt` — 동일 전환, green 확인
- [x] T014 [P] [US2] Convert `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/AvoidanceSubstanceRepositoryAdapterTest.kt` — 동일 전환, green 확인
- [x] T015 [US2] Remove `testRuntimeOnly(libs.h2)` from `infra/persistence/build.gradle.kts` (FR-008, persistence 경로)

**Checkpoint**: 모든 DB-backed 테스트가 운영 동일 엔진에서 실행(SC-002 100%), H2 완전 제거

---

## Phase 5: User Story 3 - 컨테이너 재사용/속도 (Priority: P3)

**Goal**: 컨테이너가 모듈 테스트 JVM 당 1회만 기동되어 클래스 간 재사용(Spring 컨텍스트 캐싱)되게 한다(클래스마다 재기동 금지).

**Independent Test**: 한 모듈의 여러 DB 테스트 클래스를 한 번에 실행할 때 MySQL 컨테이너가 1회만 기동됨을 **컨테이너 기동 로그**로 확인.

### Implementation for User Story 3

- [x] T016 [US3] 컨텍스트 캐싱으로 컨테이너 재기동 최소화 — DB-backed 테스트들의 컨텍스트 설정(프로퍼티·`@Import` 조합)을 동일하게 맞춰 Spring TestContext 캐시 히트를 유지. 구체: `FoodRepositoryAdapterTest` 의 `@SpringBootTest(properties=["...generate_statistics=true"])` 처럼 **컨텍스트를 파편화하는 프로퍼티 편차 제거/통일**(CP-2). 컨테이너는 컨텍스트당 1회 기동(모듈당 1개 아님, CP-1)임을 전제로 기동 횟수 확인
- [x] T017 [P] [US3] Testcontainers 재사용 옵트인(`testcontainers.reuse.enable`)·Docker 전제·트러블슈팅을 `specs/kb-46-mysql-testcontainers/quickstart.md` 에 맞춰 최종 점검(FR-007 명확 실패 메시지 포함)

**Checkpoint**: 컨테이너가 모듈당 1회만 기동됨을 확인(SC-003), 클래스 간 재사용 동작

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T018 [P] Grep 로 DB-backed 테스트 경로에 H2 잔존 참조 0건 확인(`libs.h2`·`org.h2`·`MODE=MySQL`) (FR-008/SC-004)
- [x] T019 [P] `app:batch` 테스트 점검 — `MeogoBatchApplicationTests` 가 datasource 미의존(현재 `:common` 만 의존)임을 확인, 컨테이너 불요면 무변경으로 문서화
- [x] T020 Run `quickstart.md` 검증 — Docker 기동 상태에서 `./gradlew test` 전체 green, 컨테이너 자동 프로비저닝 확인(SC-005)
- [x] T021 [P] Follow-up: 헌법 `constitution.md` 의 "영속: MySQL(**+H2 test**)" 문구를 "(+MySQL Testcontainers for integration tests)" 로 PATCH — 별도 거버넌스 변경(코드 아님)으로 기록/제안

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음, 즉시 시작. T002 는 T001 필요
- **Foundational (Phase 2)**: Setup 완료 필요 — **모든 스토리 차단**. T003 → T004 는 병렬 가능(다른 파일), T005 는 T003 필요
- **US1 (Phase 3)**: Foundational 완료 후. MVP
- **US2 (Phase 4)**: Foundational 완료 후. **US1 과 독립**(다른 모듈·파일) → US1 과 병렬 가능. 우선순위상 US1 먼저 권장
- **US3 (Phase 5)**: US1·US2 의 테스트 전환이 있어야 재사용을 관찰 가능 → 뒤에 배치
- **Polish (Phase 6)**: 원하는 스토리 완료 후

### User Story Dependencies

- **US1 (P1)**: Foundational 후 독립 실행 가능 — 크라운(마이그레이션 검증)
- **US2 (P2)**: Foundational 후 독립 실행 가능 — `infra:persistence` 국한, US1 과 파일 겹침 없음
- **US3 (P3)**: US1·US2 전환 결과 위에서 재사용/속도 관찰

### Within Each User Story

- US1: 테스트(T006) 먼저 작성·**FAIL 확인** → 설정 전환(T007) → 결함 수정·정합(T008·T009) → 기존 테스트 편입(T010) → H2 제거(T011)
- 커밋은 task/논리 단위마다

### Parallel Opportunities

- T003 ∥ T004 (foundational, 다른 파일)
- **US1 ∥ US2** — Foundational 완료 후 서로 다른 모듈이라 병렬 가능(인력 있으면)
- US2 내부 T012 ∥ T013 ∥ T014 (서로 다른 테스트 파일)
- Polish T018 ∥ T019 ∥ T021

---

## Parallel Example: US2 (persistence 어댑터 3종)

```bash
# Foundational 완료 후, 세 어댑터 테스트를 병렬 전환:
Task: "Convert FoodRepositoryAdapterTest to MySqlIntegrationSpec"
Task: "Convert MenuScanRepositoryAdapterTest to MySqlIntegrationSpec"
Task: "Convert AvoidanceSubstanceRepositoryAdapterTest to MySqlIntegrationSpec"
```

---

## Implementation Strategy

### MVP First (US1 only)

1. Phase 1 Setup → Phase 2 Foundational (컨테이너 설정)
2. Phase 3 US1: 마이그레이션-검증 테스트 Red → Flyway ON/validate → 결함 수정·시드 정합 → 기존 api 테스트 편입
3. **STOP & VALIDATE**: `./gradlew :app:api:test` 전부 green + 마이그레이션 실제 적용 확인 → 핵심 가치(SC-001) 달성

### Incremental Delivery

1. Setup + Foundational → 기반 준비
2. US1 → 마이그레이션 검증(MVP, SC-001)
3. US2 → 영속 실 엔진 + H2 완전 제거(SC-002/SC-004)
4. US3 → 재사용/속도 확정(SC-003)
5. Polish → 잔존 H2 확인·batch 점검·quickstart 검증·헌법 문구 후속

---

## Notes

- **첫 컨테이너 부팅 리스크(T008)** 가 이 작업의 실질 난이도. tasks 착수 전 로컬 docker MySQL 로 전체 마이그레이션을 사전 적용해보면 T008 범위를 미리 가늠할 수 있음(메모 `flyway-migration-validation-gap`)
- 운영 `src/main` 무변경 원칙 준수 — 변경은 build·test 소스·test 리소스에 국한
- [P] = 다른 파일·의존 없음. 각 스토리는 독립 완료·테스트 가능
- 커밋은 task/논리 단위마다
