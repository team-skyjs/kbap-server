# Research: 아키텍처 단순화 (KB-134)

조사 근거: 현행 코드베이스 인벤토리(2026-07-13, develop) — 도메인 port 6종, persistence 34파일, JPA 연관관계 1건(`FoodJpaEntity.@OneToMany`), FK 제약은 활성 테이블 전부 스키마에 존재, MongoDB 는 빌드 파일 사용처 0건(yml·compose·카탈로그 잔재만).

## D1. 헌법 개정 — 원칙 III·IV 대체 (MAJOR v3.0.0)

- **Decision**: 이 기능의 첫 산출물로 헌법을 개정한다. III(계층 의존: port-only·runtimeOnly 조립) → "부트앱 → application → 도메인 → :core, 도메인 서비스 public 창구", IV(영속 캡슐화: :infra:persistence 집결) → "영속은 소유 도메인 모듈 안에 internal". II·Additional Constraints 는 문구 동기화. 새 구조는 ADR-0012로 기록하고 ADR-0006·0008 을 supersede.
- **Rationale**: Governance 가 "헌법은 모든 관행에 우선"이라 개정 없이 구현하면 전 PR 이 위반. 원칙 재정의 = MAJOR.
- **Alternatives considered**: 개정 없이 Complexity Tracking 으로만 정당화 — 기각(일회성 waiver 가 아니라 영구 구조 변경).

## D2. 모듈·패키지 리네임 매핑

- **Decision**:

  | 현행 | 변경 후 | 패키지 |
  |------|--------|--------|
  | `:core:kernel` | `:core` | `com.meogo.core.kernel` → `com.meogo.core` |
  | `:core:<도메인>` ×6 | `:domain:<도메인>` | `com.meogo.core.<d>` → `com.meogo.domain.<d>` |
  | `:infra:persistence` | 삭제 | `com.meogo.infra.persistence.<d>` → `com.meogo.domain.<d>` 로 흡수 |

  부트 진입점(`com.meogo.MeogoApiApplication`)이 패키지 루트라 스캔·AutoConfigurationPackages 는 새 패키지를 자동 커버(설정 무변경).
- **Rationale**: KB-101 흡수 결정. US1 대규모 이동과 함께 하지 않으면 두 번 이동.
- **Alternatives considered**: 모듈 경로만 바꾸고 패키지 유지 — 기각(이름과 실체 불일치가 영구화, 이슈 DoD 가 패키지 포함 명시).

## D3. 도메인 서비스 = 유일 public 창구, 영속은 internal

- **Decision**: 도메인별 `<도메인>Service`(`@Service`, public) 신설 — `MemberService`·`FoodService`·`AvoidanceSubstanceService`·`ScanHistoryService`. 기존 어댑터의 구현 로직(엔티티 변환 호출·fetch join 조회)을 서비스가 흡수하고, port 시그니처의 의미를 서비스 메서드로 승계. 엔티티·Spring Data 리포지토리·Reconstitutor 는 `internal`. 도메인 모델·에러코드·예외는 public 유지(application 이 사용).
- **Rationale**: Gradle 모듈 = 컴파일 단위 → internal 을 컴파일러가 강제. port 없이 경계 유지가 이 설계의 핵심 전제(US2).
- **Alternatives considered**: 서비스 없이 리포지토리를 public 으로 — 기각(영속 기술이 application 에 노출, 경계 소멸).

## D4. 리포지토리 port 6종의 처리

| port (현행 위치) | 구현체 | 처리 |
|---|---|---|
| `MemberRepository` (core:member) | MemberRepositoryAdapter | 삭제 → `MemberService` 메서드로 승계 |
| `FoodRepository` (core:food) | FoodRepositoryAdapter | 삭제 → `FoodService` |
| `FoodScoringSource` (core:food) | FoodScoringSourceAdapter | 삭제 → `FoodService`(스코어링 조회 메서드) — batch 가 직접 주입 |
| `AvoidanceSubstanceRepository` (core:avoidance) | AvoidanceSubstanceRepositoryAdapter | 삭제 → `AvoidanceSubstanceService` |
| `ScanHistoryRepository` (core:scan) | ScanHistoryRepositoryAdapter | 삭제 → `ScanHistoryService` |
| `RefreshTokenStore` (core:member) | RefreshTokenRedisAdapter | **인터페이스 삭제, Redis 구현을 `:domain:member` 의 public 구체 클래스 `RefreshTokenStore` 로**(이름 승계) — data-redis 의존이 member 모듈로 이동 |

- **Decision**: 위 표. 단 `ScannedNameInterpreter`(kernel)와 `:infra:llm` 의 seam, application 내부 auth seam(`SocialTokenVerifier` 등)은 **리포지토리 port 가 아니므로 유지**(외부 시스템 클라이언트 — `:infra:llm` 은 명시적 비대상).
- **Rationale**: 이슈 스코프는 "리포지토리 port 폐기". LLM·Firebase seam 은 페이크 테스트 격리에 여전히 필요하고 폐기 실익 없음.
- **Alternatives considered**: RefreshTokenStore 를 별도 auth 모듈로 — 기각(모듈 수 증가, 현행도 member 컨텍스트 소유).

## D5. 참조 id — 값 클래스 + AttributeConverter (사용자 결정)

- **Decision**: `@JvmInline value class FoodId(val value: Long)`·`MemberId` 를 **`:core`** 에 두고(여러 컨텍스트가 참조하는 공유 vocabulary — LanguageCode 전례), `IdConverter<T>` base + 타입별 `@Converter(autoApply = true)` 등록도 `:core` 에 함께 둔다. 적용 범위: **FK 참조 필드**(ScanHistory.memberId/foodId, FoodAvoidanceSubstance.foodId)와 대응 도메인 모델 필드. 자기 PK 는 BaseEntity 의 `Long id` 유지.
- **Rationale**: id 혼동을 컴파일 단계 차단(KB-101 결정, 사용자 채택). 소유 도메인 모듈에 두면 scan→member 도메인 간 의존이 생겨 원칙 II 위반 — `:core` 가 유일한 합법 위치. Hibernate 는 Kotlin 값 클래스를 자동 언랩하지 않으므로 컨버터 필수(이슈 명시).
- **Alternatives considered**: Long 유지 — 사용자 기각. 값 클래스를 소유 도메인에 — 도메인 간 의존 발생으로 기각.

## D6. BaseEntity·EntityStatus → `:core`, 의존 처리

- **Decision**: `com.meogo.core.persistence.BaseEntity`·`EntityStatus` 로 이동. `:core` 는 `io.spring.dependency-management` + Boot BOM 을 적용하고 `compileOnly(jakarta.persistence-api)` + `compileOnly(hibernate-core)`(@SQLRestriction·@CreationTimestamp·@UpdateTimestamp 가 Hibernate 애너테이션) + `compileOnly(spring-data-jpa 불요)` 만 얹는다. 런타임 제공은 도메인 모듈의 data-jpa 스타터가 담당. `:core` 본체는 Spring-free 유지(스프링 클래스 사용 코드 없음).
- **Rationale**: 전 도메인 모듈이 상속해야 하므로 공유 최하층에만 둘 수 있다. compileOnly + BOM 으로 버전 하드코딩 없이 해결.
- **Alternatives considered**: 카탈로그에 버전 직접 고정 — 기각(Boot 업그레이드 시 이중 관리). 도메인별 복제 — 기각(6벌 중복).

## D7. JPA 연관관계 제거 — 실제 대상 1건

- **Decision**: 코드베이스 유일 연관관계 `FoodJpaEntity.@OneToMany(cascade = ALL, orphanRemoval = true)` → `food_avoidance_substance` 자식 컬렉션을 끊고, 자식 엔티티는 `foodId: FoodId` 컬럼만 든다. cascade/orphanRemoval 이 하던 저장·삭제 동기화는 `FoodService` 가 명시적으로 수행(자식 리포지토리 save/delete). 조회는 id(목록) 일괄 조회로 대체(기존 어댑터의 fetch join 자리).
- **Rationale**: 지연 로딩 소멸 → N+1·LazyInitializationException 구조적 불가(US3). 나머지 엔티티는 이미 id 참조라 무변경.
- **Alternatives considered**: 없음(이슈 DoD 직행). 주의: cascade 제거는 저장 경로 동작 보존 테스트(food 저장 시 자식 교체)로 회귀 방지.

## D8. FK 제약 — 신규 마이그레이션 불요 (검증만)

- **Decision**: 활성 테이블의 FK 는 스키마에 전부 존재 — `fk_fas_food`·`fk_fas_substance`(food_avoidance_substance), `fk_scan_history_member`·`fk_scan_history_food`(scan_history). member 는 참조 컬럼 없음. **신규 Flyway 마이그레이션 0건 예상**, 구현 시 로컬 MySQL 부팅으로 최종 확인만 한다. ON DELETE 정책 없음(소프트 삭제 구조 — 이슈 결정).
- **Rationale**: 연관관계 애너테이션 제거는 Hibernate 의 인지만 없앨 뿐 스키마 제약과 무관. 스키마 owner = Flyway 이므로 Hibernate 가 FK 를 모르는 것이 정상.
- **Alternatives considered**: 해당 없음(사실 확인 결과).

## D9. 테스트 전략 — 통합 테스트로 이동 (사용자 결정, mockk 미도입)

- **Decision**: 페이크 port 기반 유스케이스 단위 테스트를 폐기하고 시나리오를 두 층으로 흡수한다:
  1. **도메인 모듈 통합 테스트** — 기존 `*RepositoryAdapterTest`(+ 컨텍스트별 TestApp)를 각 도메인 모듈 `src/test` 로 옮겨 `<도메인>ServiceTest` 로 전환(실물 Spring Data + MySQL Testcontainers). 영속 의미(랭킹 카운트·소프트삭제·match key 등) 검증 승계.
  2. **app:api MockMvc 통합 테스트** — 유스케이스 조합 로직(프로필 병합·언어 폴백·스캔 이력 기록·탈퇴·로그인)의 시나리오를 기존 컨트롤러 테스트(MemberControllerTest 등)에 흡수·보강. 삭제되는 페이크 테스트(LoginUseCaseTest·WithdrawUseCaseTest·ScanUseCaseTest·GetFoodDetailUseCaseTest·MemberAvoidedSubstanceProviderTest·AvoidanceScoringJobTest 의 port 페이크 부분)의 given/when/then 시나리오 목록을 tasks 에서 1:1 매핑해 유실 방지.
  3. **순수 로직 단위 테스트 유지** — 도메인 모델·정책·리졸버·파서(스프링 불요)는 그대로. auth 의 `SocialTokenVerifier` 페이크, batch 의 LLM seam 페이크도 유지(D4).
- **Rationale**: 사용자 선택. 의존성 추가 없음, 유스케이스가 얇아져(서비스 조합) 통합 층 검증이 실질 커버리지와 일치.
- **Alternatives considered**: mockk 도입(추천했으나 기각), 도메인 서비스 인터페이스화(port 재생산이라 기각).

## D10. testFixtures(Testcontainers 공통 설정) 위치

- **Decision**: `MySqlContainerConfig`·`RedisContainerConfig` 를 `:core` 의 testFixtures 로 이동(`java-test-fixtures` 플러그인). 소비자: 각 도메인 모듈 테스트 + `:app:api`·`:app:batch` 테스트.
- **Rationale**: 전 도메인이 공유하는 최하층. persistence 소멸 후 유일한 공통 자리.
- **Alternatives considered**: `:testsupport` 모듈 신설 — 기각(모듈 수 증가 역행). 각 도메인 복제 — 기각.

## D11. 빌드 구성 개정

- **Decision**:
  - `meogo.domain-conventions` ← kotlin-common + **kotlin-spring + kotlin-jpa + dependency-management + Boot BOM + `api(project(":core"))` + `implementation(data-jpa)` + `runtimeOnly(mysql)` + 테스트 공통(spring-boot-starter-test·kotest-extensions-spring·testFixtures(:core))**. research·review 도 동일 적용(영속 코드가 없을 뿐 — 예외 아키타입을 만들지 않는다).
  - `:domain:member` 만 `implementation(data-redis)` 추가(RefreshTokenStore).
  - `:app:api`·`:app:batch`: `runtimeOnly(:infra:persistence)` 제거, 도메인 모듈은 application 전이(런타임)로 충분하나 batch 는 현행대로 필요 도메인 `implementation` 유지.
  - `settings.gradle.kts` include 개편, 카탈로그에서 `spring-boot-starter-data-mongodb` 삭제.
- **Rationale**: 도메인 모듈이 Spring·JPA 를 갖게 되는 것이 이번 구조의 핵심 트레이드오프(이슈 승인). `implementation(data-jpa)` 라 상위 컴파일 클래스패스로 새지 않음.
- **Alternatives considered**: research/review 용 별도 pure 아키타입 유지 — 기각(플러그인 2벌 관리, 실익 없음).

## D12. MongoDB 잔재 제거 범위

- **Decision**: (1) api·batch 의 프로필 yml 4쌍에서 `spring.data.mongodb` 블록 삭제, (2) `docker-compose.yml` mongo 서비스·볼륨·depends_on·MONGODB_URI 삭제, (3) `docker-compose.prod.yml` 의 `SPRING_AUTOCONFIGURE_EXCLUDE` mongo 4종·주석 삭제, (4) 카탈로그 `spring-boot-starter-data-mongodb` 삭제, (5) 헌법·CLAUDE.md·아키텍처 문서의 MongoDB 표기 제거. 빌드 파일 사용처는 이미 0건이라 코드 변경 없음.
- **Rationale**: Document·MongoRepository 코드 0줄 — 순수 잔재.
- **Alternatives considered**: 해당 없음.

## D13. ArchUnit 재작성 (새 규칙)

- **Decision**: `ModuleBoundaryTest` 를 새 구조 기준으로 다시 쓴다 — (1) `:core` 는 spring·domain·application·app·common 무의존(jakarta.persistence·org.hibernate.annotations 는 허용 — BaseEntity·컨버터 예외), (2) 도메인 모듈 간 직접 의존 금지(유지), (3) `@Entity` 는 `com.meogo.domain..` 에만, (4) **JPA 연관관계 애너테이션(@OneToMany·@ManyToOne·@OneToOne·@ManyToMany) 전면 금지**, (5) 컨트롤러 매핑 `/api/v` 시작(신규 — 기존 후속 과제 해소), (6) app:api 는 엔티티·도메인 내부 미참조(internal 이 컴파일 차단하지만 이중 방어), (7) application → infra·app 금지(유지), (8) common 경계(유지), (9) AvoidanceSubstanceCode label-only 회귀(유지).
- **Rationale**: 이슈 DoD 명시 + Test-First 진입점(새 규칙 Red → 이동 Green).
- **Alternatives considered**: 해당 없음.
