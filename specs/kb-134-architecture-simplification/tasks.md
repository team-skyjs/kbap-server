# Tasks: 아키텍처 단순화 — persistence 모듈 해체·port 폐기·JPA 연관관계 제거

**Input**: Design documents from `/specs/kb-134-architecture-simplification/`

**Prerequisites**: plan.md, spec.md, research.md(D1~D13), data-model.md(이동표), contracts/README.md, quickstart.md

**Tests**: Test-First(헌법 I) — 각 스토리의 테스트를 먼저 쓰고 Red 를 확인한다. 리팩토링 특성상 두 종류의 Red 를 구분한다: (a) **새 구조 기대 테스트**(ArchUnit 신규 규칙·도메인 서비스 테스트)는 대상 미존재/구조 불일치로 Red → 이동·구현으로 Green, (b) **동작 보존 테스트**(계약·cascade 승계)는 변경 전 green 을 먼저 확인하고 리팩토링 후에도 green 유지가 곧 검증이다. 신규 ArchUnit 규칙은 US1·US3 완료 전까지 의도적으로 Red 상태로 남는다(로컬 브랜치 — 커밋은 태스크 단위로 하되 ModuleBoundaryTest 만 제외 실행으로 중간 green 을 확인).

**Organization**: 스토리별 phase. 단 **모듈 리네임(US4)의 기계적 실행은 Phase 2(Foundational)** 에 둔다 — 리네임을 먼저 하지 않으면 US1 의 대규모 파일 이동을 두 번 하게 된다(플랜 D2). US4 phase 는 리네임 결과의 검증·문서 표기만 남는다.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Setup — 헌법·ADR (새 구조의 법적 근거)

**Purpose**: 헌법이 모든 관행에 우선하므로, 개정 없이 구현하면 전 커밋이 위반이 된다(플랜 Constitution Check).

- [X] T001 헌법 MAJOR 개정 v3.0.0 — `.specify/memory/constitution.md`: 원칙 III(port-only·runtimeOnly 조립) → "부트앱 → application → 도메인 모듈 → :core, 도메인 서비스 public 창구·영속 internal" 로, 원칙 IV(:infra:persistence 집결) → "영속은 소유 도메인 모듈 안에 internal, 컴파일러+ArchUnit 강제" 로 대체. 원칙 II 문구(`:core:*`→`:domain:*`, 공유 id 값 클래스의 `:core` 배치)·Additional Constraints(MongoDB 삭제, 모듈 표기) 동기화. Sync Impact Report 갱신
- [X] T002 [P] ADR-0012 작성 — `docs/adr/0012-dissolve-persistence-module-and-ports.md`(0011 은 기존재 — 번호 이월): 결정(persistence 해체·port 폐기·internal 경계·도메인 서비스 창구·연관관계 제거·id 값 클래스·모듈 리네임)과 근거, ADR-0006·ADR-0008 supersede 표기(두 파일 상태 갱신 포함)

**Checkpoint**: 헌법·ADR 이 새 구조를 승인한 상태 — 코드 변경 시작 가능

---

## Phase 2: Foundational — ArchUnit Red + 모듈 리네임 + :core 재편 (모든 스토리 차단 해제)

**Purpose**: 새 구조의 기대를 테스트로 먼저 고정(Red)하고, 모듈·패키지 좌표를 최종 위치로 옮겨 이후 이동이 한 번에 끝나게 한다.

**⚠️ CRITICAL**: 이 phase 완료 전에 스토리 작업 시작 금지. T004~T008 은 전 소스를 건드리므로 **순차 실행**(병렬 금지).

- [X] T003 ArchUnit 신규 규칙 재작성(Red 확인) — `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt` 를 research D13 의 9규칙 + 도메인 모델 ORM-free 규칙(10)으로 교체: (1) `:core` 무의존(jakarta.persistence·org.hibernate.annotations 허용) (2) 도메인 모듈 간 의존 금지 (3) `@Entity` 는 `com.meogo.domain..` 에만 (4) JPA 연관관계 애너테이션 4종 전면 금지 (5) 컨트롤러 매핑 `/api/v` 시작 (6) app:api 엔티티·도메인 내부 미참조 (7) application → infra·app 금지 (8) common 경계 (9) AvoidanceSubstanceCode label-only (10) 도메인 모델(@AggregateRoot 클래스·엔티티 외 도메인 클래스)은 jakarta.persistence 미의존(FR-004 강제). 실행해 Red 확인(구 구조라 3·4 등 실패)
- [X] T004 모듈 리네임 — `settings.gradle.kts` include 개편(`:core:kernel`→`:core`, `:core:<도메인>`→`:domain:<도메인>` 6종) + 디렉터리 `git mv`(`core/kernel`→`core`, `core/<도메인>`→`domain/<도메인>`) + 전 모듈 build 파일의 project 경로 참조 갱신
- [X] T005 패키지 리네임 — `com.meogo.core.kernel`→`com.meogo.core`, `com.meogo.core.<도메인>`→`com.meogo.domain.<도메인>` (소스 디렉터리 이동 + 전 저장소 import 일괄 치환: application·infra:persistence(잔존 중)·infra:llm·app:api·app:batch 포함)
- [X] T006 컨벤션 플러그인 개정 — `buildSrc/src/main/kotlin/meogo.domain-conventions.gradle.kts`: kotlin-common + kotlin-spring + kotlin-jpa + dependency-management + Boot BOM + `api(project(":core"))` + `implementation(data-jpa)` + `runtimeOnly(mysql)` + 테스트 공통(spring-boot-starter-test·kotest-extensions-spring·`testImplementation(testFixtures(project(":core")))`) (research D11 — research·review 도 동일 적용, 예외 아키타입 없음)
- [X] T007 :core 재편 — `BaseEntity`·`EntityStatus` 를 `infra/persistence` 에서 `core/src/main/kotlin/com/meogo/core/persistence/` 로 이동, `core/build.gradle.kts` 에 dependency-management + Boot BOM + `compileOnly(jakarta.persistence-api)`·`compileOnly(hibernate-core)` 추가(카탈로그에 라이브러리 좌표 등록, 버전은 BOM 관리 — research D6)
- [X] T008 Testcontainers 공통 설정 이동 — `MySqlContainerConfig`·`RedisContainerConfig` 를 `core/src/testFixtures/kotlin/com/meogo/core/testsupport/` 로 이동(`java-test-fixtures` 플러그인 적용), 소비자(`:app:api`·`:app:batch`·잔존 `:infra:persistence`) 의존 갱신 (research D10)
- [X] T009 중간 검증 — ModuleBoundaryTest 에 Kotest 태그(`arch`)를 달고 `./gradlew build -Dkotest.tags="!arch"` 로 태그 제외 전 테스트 green 확인(api 컨트롤러 테스트 포함, ModuleBoundaryTest 만 의도적 Red 유지), 커밋

**Checkpoint**: 좌표 확정(`:domain:*`·`:core`)·빌드 green(신규 ArchUnit 제외) — 도메인별 이식 시작 가능

---

## Phase 3: User Story 1 — 도메인 하나를 고칠 때 한 모듈만 열면 된다 (P1) 🎯 MVP

**Goal**: persistence 해체 — 엔티티·리포지토리를 도메인 모듈로(internal), 어댑터·port 폐기, 도메인 서비스가 public 창구. application·batch 는 도메인 서비스 조합으로 전환.

**Independent Test**: 임의 도메인의 영속 코드가 그 도메인 모듈 안에 있고 `:infra:persistence`·port·`RepositoryAdapter` 가 0건 (spec US1 AC).

**시나리오 승계 매핑표** (페이크 port 테스트 → 새 위치, 유실 방지 — research D9):

| 삭제되는 테스트 | 승계 위치 |
|---|---|
| `MemberRepositoryAdapterTest` | `:domain:member` `MemberServiceTest` (Testcontainers) |
| `RefreshTokenRedisAdapterTest` | `:domain:member` `RefreshTokenStoreTest` |
| `FoodRepositoryAdapterTest`·`FoodScoringSourceAdapterTest`·`FoodMatchKeySyncTest` | `:domain:food` `FoodServiceTest`(+MatchKeySyncTest 이동) |
| `AvoidanceSubstanceRepositoryAdapterTest` | `:domain:avoidance` `AvoidanceSubstanceServiceTest` |
| `ScanHistoryRepositoryAdapterTest` | `:domain:scan` `ScanHistoryServiceTest` |
| `LoginUseCaseTest`(페이크 MemberRepository·RefreshTokenStore 부분) | `AuthControllerTest` 보강(SocialTokenVerifier 페이크는 유지) |
| `WithdrawUseCaseTest` | `MemberControllerTest` 탈퇴 시나리오 보강 |
| `ScanUseCaseTest`·`FakeScanHistoryRepository` 사용 테스트 | 스캔 컨트롤러 MockMvc + `ScanHistoryServiceTest` |
| `GetFoodDetailUseCaseTest`·`MemberAvoidedSubstanceProviderTest` | 음식 상세 컨트롤러 MockMvc 보강 |
| `MemberProfileUseCase`·`MemberRankingUseCase` 페이크 단위 테스트 | `MemberControllerTest`(기존 MockMvc 시나리오가 대부분 커버 — 부족분 보강) |
| `AvoidanceScoringJobTest`(FoodScoringSource 페이크 부분) | batch 통합 테스트(실물 FoodService + Testcontainers, LLM seam 페이크 유지) |

### Tests for User Story 1 (Test-First — 먼저 작성, Red 확인) ⚠️

- [X] T010 [P] [US1] `domain/member/src/test/kotlin/com/meogo/domain/member/MemberServiceTest.kt` + `RefreshTokenStoreTest.kt` + TestApp 작성 — 구 어댑터 테스트 시나리오(프로필 왕복·scan_count 영속·소프트삭제·토큰 TTL) 승계, MemberService 미존재로 Red
- [X] T011 [P] [US1] `domain/food/src/test/kotlin/com/meogo/domain/food/FoodServiceTest.kt` + TestApp 작성 — 구 FoodRepositoryAdapterTest·FoodScoringSourceAdapterTest 시나리오(조회·검색 커서·match key·스코어링 소스) 승계, Red
- [X] T012 [P] [US1] `domain/avoidance/src/test/kotlin/com/meogo/domain/avoidance/AvoidanceSubstanceServiceTest.kt` + TestApp 작성 — 카탈로그 조회·소프트삭제 스킵 시나리오 승계, Red
- [X] T013 [P] [US1] `domain/scan/src/test/kotlin/com/meogo/domain/scan/ScanHistoryServiceTest.kt` + TestApp 작성 — 이력 기록·최근 10건 조회 시나리오 승계, Red

### Implementation for User Story 1

- [X] T014 [P] [US1] member 이식 — 엔티티·리포지토리·MemberStatus·MemberProfileJson 을 `domain/member/src/main/kotlin/com/meogo/domain/member/` 로 이동(**internal**), `MemberService`(public, 구 어댑터 로직 흡수) 신설, `RefreshTokenRedisAdapter` → public 구체 클래스 `RefreshTokenStore` 로 전환, `MemberRepository`·`RefreshTokenStore` port 삭제, `domain/member/build.gradle.kts` 에 `implementation(data-redis)` 추가 → T010 Green
- [X] T015 [P] [US1] food 이식 — FoodJpaEntity·FoodJpaRepository·FoodAvoidanceSubstanceJpaEntity 를 `domain/food/src/main/kotlin/com/meogo/domain/food/` 로 이동(**internal**, 연관관계는 이 단계에선 현행 유지 — US3 에서 제거), `FoodService`(조회·검색·스코어링 소스 메서드) 신설, `FoodRepository`·`FoodScoringSource` port 삭제 → T011 Green
- [X] T016 [P] [US1] avoidance 이식 — 엔티티·리포지토리·Reconstitutor 이동(**internal**), `AvoidanceSubstanceService` 신설, `AvoidanceSubstanceRepository` port 삭제 → T012 Green
- [X] T017 [P] [US1] scan 이식 — 엔티티·리포지토리 이동(**internal**), `ScanHistoryService` 신설, `ScanHistoryRepository` port 삭제 → T013 Green
- [X] T018 [US1] application:client 전환 — 전 유스케이스(auth·member·food·home·scan)의 port 주입을 도메인 서비스 주입으로 교체 (`application/client/src/main/kotlin/com/meogo/application/client/**`) — 유스케이스 public 시그니처(Input/Result)는 무변경, `@Transactional` 경계 유지 (HomeQueryUseCase 등 여러 도메인 서비스 조합 파일이 있어 T014~T017 완료 후 순차 1회로 처리)
- [X] T019 [US1] app:batch 전환 — `ScoringJobConfig` 가 `FoodService`·`AvoidanceSubstanceService` 를 주입해 잡에 배선. 구현 편차: `AvoidanceScoringJob` 은 서비스 직접 의존 대신 **람다 협력자**(`nextChunk`·`findSubstances` — 잡 소유 파라미터, port 아님)를 받아 기존 배치 단위 테스트 시나리오 전부를 페이크→람다로 보존(무거운 batch Testcontainers 통합 불필요). runner 게이팅은 `ScoringRunnerConfig` 로 분리해 게이팅 테스트가 스텁 잡으로 검증
- [X] T020 [US1] `:infra:persistence` 모듈 삭제 — 잔여 파일 0 확인 후 디렉터리 삭제, `settings.gradle.kts` 에서 include 제거, `app/api/build.gradle.kts`·`app/batch/build.gradle.kts` 의 `runtimeOnly(:infra:persistence)`·testFixtures 참조 제거
- [X] T021 [US1] 부팅·전체 검증 — `./gradlew build -Dkotest.tags="!arch"` green(api·batch `@SpringBootTest` 부팅·컨트롤러 테스트 포함 — 도메인 서비스 빈 조립 확인), 커밋

**Checkpoint**: US1 AC 충족 — persistence·port·어댑터 0건, 도메인 모듈 자족. ArchUnit 은 연관관계 규칙만 Red 로 남음

---

## Phase 4: User Story 2 — 도메인 경계는 컴파일러가 지킨다 (P1)

**Goal**: internal 경계 실증 + 삭제된 페이크 테스트 시나리오의 통합 테스트 승계 완결.

**Independent Test**: 도메인 밖에서 엔티티 참조 시 컴파일 실패, ArchUnit 새 규칙 통과(연관관계 규칙 제외), 승계 매핑표 전 행 커버.

### Tests for User Story 2 (Test-First) ⚠️

- [X] T022 [P] [US2] 컨트롤러 통합 테스트 보강(먼저 작성 — 기존 동작이라 green 시작이 정상인 **동작 보존 테스트**) — 매핑표의 login·withdraw·scan·food detail·avoided substance 시나리오 중 기존 MockMvc 테스트가 커버하지 않는 케이스를 `app/api/src/test/kotlin/com/meogo/app/api/**` 에 추가
- [X] T023 [P] [US2] batch 스코어링 테스트 승계 — 구현 편차(T019): 잡을 람다 협력자로 재구성해 `AvoidanceScoringJobTest`·`ScoringJobRunnerTest`·`AvoidanceScoringSmokeTest` 의 기존 시나리오 전부를 페이크→람다로 무손실 보존(Testcontainers 통합 전환 불필요), 게이팅 테스트는 스텁 잡 + `ScoringRunnerConfig` 로 재작성

### Implementation for User Story 2

- [X] T024 [US2] 페이크 port 테스트 삭제(T018 에서 선행 — 컴파일 유지 필요) 및 매핑표 대조 완료. 보강 4건: 재로그아웃 멱등(Auth)·온보딩 정규화/맵기 보존(Member)·혼합 이력 READY 필터+음식당 1건·매칭 0건 스캔 1회(Scan)·언어 미설정 영어 폴백(Home). 수용 손실 2건(문서화): 로그인 saveNew 동시성 경합 재조회 폴백(페이크 전용 재현 — 중복 예외 자체는 MemberServiceTest 커버), 만료 refresh 의 잔여 세션 폐기 후속동작(만료 파싱 거절은 TokenTest 커버) — `application/client/src/test` 의 Fake* 파일·구 유스케이스 단위 테스트 제거(매핑표 전 행의 승계 완료를 먼저 대조), `application/client/build.gradle.kts` 테스트 의존 정리
- [X] T025 [US2] internal 경계 스팟체크 — application:client 에 `MemberJpaEntity` import 를 임시 추가해 컴파일 실패 확인 후 원복(결과를 커밋 메시지에 기록), ArchUnit ModuleBoundaryTest 실행해 연관관계 규칙(4) 외 전부 green 확인

**Checkpoint**: 경계 검증 완료·시나리오 유실 0건 — 전체 테스트 스위트가 새 구조에서 유의미

---

## Phase 5: User Story 3 — N+1 이 구조적으로 발생할 수 없다 (P2)

**Goal**: 유일 연관관계(FoodJpaEntity @OneToMany) 제거, 참조를 id 값 클래스로 전환, FK 는 스키마 확인.

**Independent Test**: 연관관계 애너테이션 grep 0건, ArchUnit 규칙(4) green, food 저장 시 자식 교체가 cascade 시절과 동일 동작.

### Tests for User Story 3 (Test-First) ⚠️

- [X] T026 [US3] 테스트 선작성(Red 확인) — 조정: 프로덕션에 cascade 쓰기 경로가 없음을 확인(자식 쓰기는 시드·테스트뿐, port 에 save(food) 부재)해 "cascade 보존" 대신 자식 명시 시드 + 값 클래스 바인딩 + 문장 수 상수(음식1+성분1) 검증으로 대체. 원문: — `FoodServiceTest` 에 자식(food_avoidance_substance) 교체 저장·삭제 시나리오 추가(현행 cascade 구현으로 green 확인 — 이후 명시 관리 전환에도 green 유지가 검증), JPQL/파라미터에 값 클래스 바인딩 검증 케이스 포함(값 클래스 도입 전이라 Red)

### Implementation for User Story 3

- [X] T027 [P] [US3] id 값 클래스 + 컨버터 — `core/src/main/kotlin/com/meogo/core/id/` 에 `@JvmInline value class FoodId`·`MemberId` + `IdConverter<T>` base + `FoodIdConverter`·`MemberIdConverter`(`@Converter(autoApply = true)`) 작성 (research D5)
- [X] T028 [US3] 참조 필드 전환 — `ScanHistoryJpaEntity.memberId/foodId`·`FoodAvoidanceSubstanceJpaEntity.foodId` 와 대응 도메인 모델(`ScanHistory`·`FoodAvoidanceSubstance`)·서비스 시그니처를 값 클래스로 교체(`ScanHistoryServiceTest` 시그니처·바인딩 케이스 갱신 포함), 유스케이스 경계(Input/Result)는 Long 유지(API 계약 불변)
- [X] T029 [US3] @OneToMany 제거 — `FoodJpaEntity` 의 자식 컬렉션·cascade·orphanRemoval 삭제, `FoodService` 가 자식 리포지토리로 명시 save/delete(교체 저장 = 기존 자식 delete → 신규 insert)·id 목록 일괄 조회로 조립 (data-model §4) → T026 전부 green
- [X] T030 [US3] 검증 — 연관관계 애너테이션 grep 0건, ArchUnit 전 규칙 green(이 시점부터 arch 태그 제외 없이 상시 실행 복귀), 로컬 MySQL(docker) DROP+CREATE 후 api 부팅으로 Flyway·FK 제약(fk_fas_*·fk_scan_history_*) 존재 확인 — 신규 마이그레이션 불요 재확인(research D8), 커밋

**Checkpoint**: 연관관계 0건·값 클래스 적용·FK 스키마 강제 확인

---

## Phase 6: User Story 4 — 모듈 이름이 역할을 그대로 말한다 (P2)

**Goal**: Phase 2 에서 실행된 리네임의 완결 검증 + 문서 표기 정합.

**Independent Test**: 새 경로로 전체 빌드 성공, 옛 좌표(`:core:kernel`·`:core:<도메인>`·`com.meogo.core.<도메인>`·`com.meogo.infra.persistence`) 잔재 grep 0건.

- [X] T031 [P] [US4] 잔재 검증 — quickstart 2번 grep 세트 실행(옛 모듈 경로·옛 패키지·persistence 참조 0건), BaseEntity·EntityStatus 상속이 전 엔티티에서 `com.meogo.core.persistence` 를 가리키는지 확인
- [X] T032 [P] [US4] 아키텍처 문서 표기 갱신 — `docs/architecture/meogo-api-module-structure.md`·`docs/architecture/meogo-conventions.md` 의 모듈 트리·패키지·"JPA 엔티티 작성"·"도메인↔JPA 변환" 절을 새 구조(도메인 내 internal 영속·도메인 서비스 창구·연관관계 금지) 기준으로 재작성

**Checkpoint**: 이름·문서·실체 일치

---

## Phase 7: User Story 5 — 쓰지 않는 것은 남기지 않는다 (P3)

**Goal**: MongoDB 잔재 제거 (코드 사용처 0건 — 설정만 정리).

**Independent Test**: mongo 관련 grep 0건 + 두 앱 부팅 green.

- [X] T033 [P] [US5] yml 정리 — `app/api/src/main/resources/application-{local,dev,staging,prod}.yml`·`app/batch/src/main/resources/application-{local,dev,staging,prod}.yml` 의 `spring.data.mongodb` 블록 8곳 + `app/batch/src/main/resources/application.yml` 의 mongodb 주석 삭제
- [X] T034 [P] [US5] compose·카탈로그 정리 — `docker-compose.yml` mongo 서비스·볼륨·depends_on·MONGODB_URI 삭제, `docker-compose.prod.yml` 의 `SPRING_AUTOCONFIGURE_EXCLUDE` mongo 4종·관련 주석 삭제, `gradle/libs.versions.toml` 에서 `spring-boot-starter-data-mongodb` 삭제
- [X] T035 [US5] 부팅 검증 — `./gradlew :app:api:test :app:batch:test` 로 `@SpringBootTest` 부팅 green(quickstart 4·5 절), `docker compose config` 로 compose 유효성 확인

**Checkpoint**: MongoDB 잔재 0건

---

## Phase 8: Polish & Cross-Cutting

- [ ] T036 [P] CLAUDE.md 갱신 — 개요·모듈 구조·기술 스택(MongoDB 삭제)·빌드 구성·컨벤션("JPA 엔티티 작성"·"도메인↔JPA 변환"·연관관계 절을 새 규칙으로) 재작성 (FR-015)
- [ ] T037 quickstart.md 전 절 실행 — 완료 판정 체크 1~5 전부 통과 확인(전체 빌드·잔재 grep·internal 스팟체크·로컬 부팅·Swagger 계약 육안 대조), 결과 기록
- [ ] T038 [P] Jira 후속 정리 — KB-134 DoD 체크 갱신 + 리뷰 태스크(KB-128·KB-129·KB-131) 본문을 새 구조 기준으로 갱신(이슈 본문의 선행 관계 결정)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1(Setup)**: 즉시 시작. T001·T002 는 [P]
- **Phase 2(Foundational)**: Phase 1 뒤. T003 먼저(Red 고정), T004→T005→T006→T007→T008→T009 **순차**(전 소스 접촉 — 병렬 금지)
- **Phase 3(US1)**: Phase 2 뒤. T010~T013 [P] → T014~T017 [P](각자 자기 도메인 테스트에 의존: T014←T010, T015←T011, T016←T012, T017←T013) → T018 → T019 → T020 → T021
- **Phase 4(US2)**: US1 뒤. T022·T023 [P] → T024 → T025
- **Phase 5(US3)**: US1 뒤(US2 와 병렬 가능하나 FoodServiceTest 파일을 T022 와 공유하지 않으므로 안전, 권장은 순차). T026 → T027 → T028 → T029 → T030
- **Phase 6(US4)**: Phase 2·3 뒤 언제든. T031·T032 [P]
- **Phase 7(US5)**: 독립 — Phase 2 이후 언제든 가능. T033·T034 [P] → T035
- **Phase 8(Polish)**: 전 스토리 뒤. T036·T038 [P], T037 은 최종

### Parallel Example: Phase 3

```bash
# 도메인 서비스 테스트 4건 동시 작성(Red):
Task: "MemberServiceTest + RefreshTokenStoreTest (domain/member)"
Task: "FoodServiceTest (domain/food)"
Task: "AvoidanceSubstanceServiceTest (domain/avoidance)"
Task: "ScanHistoryServiceTest (domain/scan)"
# 이어서 도메인 이식 4건 동시(서로 다른 모듈, application 은 건드리지 않음):
Task: "member 이식 (T014)"  Task: "food 이식 (T015)"
Task: "avoidance 이식 (T016)"  Task: "scan 이식 (T017)"
# T018(application 전환)은 공유 파일(HomeQueryUseCase 등) 때문에 단독 순차
```

---

## Implementation Strategy

- **MVP = Phase 1~3(US1)**: persistence 해체가 끝나면 이 리팩토링의 존재 이유가 달성된다. T021 체크포인트에서 멈추고 검증 가능.
- **증분 순서**: US1 → US2(경계·테스트 완결) → US3(연관관계) → US4(검증·문서) → US5(MongoDB) → Polish. US5 는 아무 때나 꽂을 수 있는 독립 작업.
- **회귀 가드**: 기존 MockMvc 컨트롤러 테스트 스위트가 API 계약의 실행 명세(contracts/README.md) — 전 phase 에서 green 유지가 SC-003 의 증거.
- **주의**: 기존 Flyway 마이그레이션 파일 이동·리네임 금지(시드 동기화 테스트가 경로 하드코딩 — 조용히 깨짐). `:infra:llm`·`:common` 비접촉(import 경로 갱신만).
- 커밋은 태스크(또는 논리 단위)마다. develop 병렬 작업과의 충돌 최소화를 위해 Phase 2(전 소스 접촉)는 빠르게 통과시킨다.
