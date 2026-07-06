# Research: MySQL Testcontainers 도입 (KB-46)

## 현황 조사 (코드 검증 결과)

| 항목 | 사실 | 근거 |
|---|---|---|
| DB-backed 테스트 위치 | `app:api`(웹 `@SpringBootTest`+MockMvc 다수), `infra:persistence`(어댑터 `@SpringBootTest` 3종), `app:batch`(컨텍스트 로드 1종) | 소스 스캔 |
| 현재 테스트 DB | 임베디드 H2, `ddl-auto=create-drop`, **Flyway off** | `app/api/src/test/resources/application.yml` |
| 마이그레이션 소유 | **`app:api` 만** (`resources/db/migration`, Flyway 의존도 api 에만) | `app/api/build.gradle.kts` |
| `infra:persistence` | Flyway·마이그레이션 **없음**. 컨텍스트별 테스트 부트앱(`FoodPersistenceTestApp` 등)으로 `@SpringBootTest` 기동, Hibernate 가 엔티티에서 스키마 생성 | 소스 스캔 |
| JSON 컬럼 처리 | `@JdbcTypeCode(SqlTypes.JSON)` — Hibernate 가 Java 레벨에서 `Map`↔JSON 직렬화. 네이티브 JSON 쿼리 0건 | `FoodJpaEntity`, 리포지토리 전수 |
| 마이그레이션 방언 | `JSON_OBJECTAGG`·`JSON_SET`·`MODIFY COLUMN`·`ADD COLUMN … AFTER` 등 **MySQL 전용**, H2 파싱 불가 | migration SQL |
| 마이그레이션 진화 | `ingredient` 생성→시드→후속 마이그레이션에서 drop/replace(KB-40) 등 **연쇄 진화**가 존재하나 테스트에서 한 번도 실행 안 됨 | migration 체인 |
| Testcontainers | 카탈로그·빌드에 **미도입** (Spring Boot 4.1 BOM 이 버전 관리) | `gradle/libs.versions.toml` |

## 결정 사항

### D-1. Testcontainers 통합 방식 — Spring Boot `@ServiceConnection` + `@Bean` 컨테이너

- **Decision**: `@TestConfiguration` 에 `@Bean @ServiceConnection fun mysql() = MySQLContainer("mysql:8.4")` 를 선언하고, DB-backed 테스트가 `@Import` 로 가져간다. `spring-boot-testcontainers` 모듈이 컨테이너의 접속 정보를 datasource 프로퍼티로 자동 연결한다.
- **Rationale**: Kotest(BehaviorSpec)+`SpringExtension` 을 쓰므로 JUnit 전용 `@Testcontainers`/`@DynamicPropertySource` 생명주기에 의존하지 않는 게 안전하다. `@ServiceConnection` 은 Spring TestContext 가 처리하므로 프레임워크 중립이고, **컨텍스트 캐싱으로 동일 설정 테스트 간 컨테이너가 자동 재사용**된다(FR-004 를 별도 싱글턴 코드 없이 충족).
- **Alternatives**:
  - 정적 싱글턴 컨테이너(companion `MySQLContainer().apply{start()}`) + `@DynamicPropertySource` → JUnit 색채가 강하고 Kotest 와 결이 안 맞음. 폴백 후보.
  - JUnit `@Testcontainers` + `@Container` → Kotest 라이프사이클과 충돌.

### D-2. 마이그레이션 검증(P1) 배치 — `app:api` 테스트에서 Flyway ON + `ddl-auto=validate`

- **Decision**: `app:api` 테스트 프로필에서 `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate` 로 전환. 컨테이너의 빈 MySQL 에 **운영과 동일한 Flyway 마이그레이션 전체가 적용**되어 스키마·시드가 만들어지고, Hibernate 는 그 스키마에 엔티티가 맞는지 검증만 한다.
- **Rationale**: 마이그레이션은 `app:api` 에만 있으므로 검증도 여기가 자연스러운 소유처(스키마 owner=api). `validate` 는 마이그레이션↔엔티티 정합 드리프트를 공짜로 함께 검출한다.
- **Risk**: 전체 마이그레이션 체인(ingredient 생성→drop→jsonify→replace)이 **처음으로** 실행되므로 잠복 결함이 드러날 수 있다. 이는 이 작업의 목적이자, 첫 실행에서 수정 과제가 나올 수 있음을 의미. `validate` 가 지나치게 시끄러우면 `none` 으로 임시 완화 후 별도 이슈화.
- **Alternatives**: `ddl-auto=none` → 엔티티 정합 검증을 포기. `create-drop` 유지 → 마이그레이션을 안 돌려 P1 무의미(기각).

### D-3. `infra:persistence` 어댑터 테스트 — 컨테이너 사용, 스키마는 Hibernate 생성 유지

- **Decision**: persistence 어댑터 테스트도 MySQL 컨테이너(실 엔진)로 전환하되, 이 모듈엔 마이그레이션이 없으므로 스키마는 현행대로 Hibernate(`create-drop`)가 생성한다.
- **Rationale**: 이 테스트의 목적은 **어댑터 매핑·쿼리 정확성**이지 마이그레이션 검증이 아니다(P2). 마이그레이션을 여기서 돌리려면 api 모듈 자원을 교차 참조해야 하는데, persistence→api 의존은 **순환**(api 가 persistence 를 runtimeOnly 로 조립)이라 불가·부적절. 실 엔진(P2)만 취하고 마이그레이션 검증(P1)은 api 소유로 둔다.
- **Alternatives**: Flyway `locations` 를 api 마이그레이션 폴더로 지정 → 모듈 경계·소유권 위반(기각). persistence 테스트를 api 로 이관 → 계층 성격 왜곡(기각).

### D-4. 컨테이너 설정 공유 — `infra:persistence` 의 `java-test-fixtures` 로 단일화

- **Decision**: `MySQLContainer` `@ServiceConnection` 설정과 공통 Kotest 베이스(BehaviorSpec + SpringExtension 조합 헬퍼)를 `infra:persistence` 의 `src/testFixtures` 에 두고, `app:api` 는 `testImplementation(testFixtures(project(":infra:persistence")))` 로 소비한다.
- **Rationale**: 두 모듈이 같은 컨테이너 설정을 공유하되 새 모듈을 만들지 않는다. persistence 는 api 에 의존하지 않으므로 **순환 없음**. MySQL 이미지 태그(`mysql:8.4`)를 test-fixtures 의 상수 **단일 출처**로 둔다(FR-009).
- **Alternatives**: 각 모듈에 베이스 클래스 중복(~20줄) → 단순하지만 이미지 태그·설정이 2곳으로 분산(FR-009 약화). test-fixtures 구성이 Kotest 와 껄끄러우면 폴백. 전용 `:test-support` 모듈 신설 → 모듈 증가로 과함(기각).

### D-5. H2 제거(FR-008)

- **Decision**: `infra:persistence` 와 `app:api` 의 `testRuntimeOnly(libs.h2)` 를 제거한다. 컨테이너가 DB-backed 경로를 담당한다.
- **Rationale**: 이중 DB 를 남기면 "H2 에서만 통과" 회귀 위험이 다시 생긴다. 규약("H2 호환 미고려")과도 일치.
- **Note**: 헌법 Additional Constraints 의 "영속: MySQL(+H2 test)" 문구는 이 변경 이후 실체와 어긋난다 → 구현 완료 후 헌법 PATCH 로 "(+MySQL Testcontainers)" 갱신 **후속 과제**(게이트 위반 아님).

### D-6. Docker 미가용 시 명확 실패(FR-007)

- **Decision**: Testcontainers 기본 동작(유효한 Docker 환경 미탐지 시 명시적 예외)을 그대로 신뢰한다. 별도 래핑 없이, quickstart 에 "DB-backed 테스트는 Docker 필요"를 문서화.
- **Rationale**: 라이브러리가 이미 실행 가능한 메시지("Could not find a valid Docker environment")를 던진다. 과설계 회피.

### D-7. 테스트 시드 ↔ 마이그레이션 시드 정합(신규 리스크)

- **Decision**: `app:api` 테스트가 `dataSource` 로 직접 삽입하던 픽스처(예: `FoodTestSeed` 된장찌개)를, Flyway 시드로 이미 존재하는 데이터와 **충돌하지 않게 재조정**한다 — (a) 마이그레이션 시드에 이미 있으면 픽스처 삽입 제거하고 시드 데이터에 의존, (b) 없으면 유지하되 유니크 제약 충돌 여부 확인, (c) 테스트 간 상태 격리(정리/롤백) 보장(FR-006).
- **Rationale**: Flyway 를 켜면 시드 마이그레이션(`seed_food_data`, `seed_real_food_avoidance_substances`)이 실제 실행되므로, 수기 픽스처와 겹치면 무결성 오류가 난다. 이는 전환 시 반드시 처리해야 하는 실작업.
- **Alternatives**: 매 테스트 `TRUNCATE` 후 재시드 → 무겁고 시드 마이그레이션 취지 훼손. 트랜잭션 롤백 격리 우선.

## 공식 문서(Spring Boot Testcontainers) 대조 — 재사용 기대치 정정

출처: <https://docs.spring.io/spring-boot/reference/testing/testcontainers.html>

- **CP-1 (기대치 정정)**: 컨테이너는 "모듈당 1회"가 아니라 **"Spring 애플리케이션 컨텍스트당 1회"** 기동된다("Container beans are created and started **once per application context**"). 이 코드베이스는 한 모듈에 컨텍스트가 여러 개다 — 특히 `infra:persistence` 는 컨텍스트별 테스트 부트앱(`FoodPersistenceTestApp`·`ScanPersistenceTestApp`·`AvoidancePersistenceTestApp`) 3개라 **컨텍스트 3개 → 컨테이너 최대 3회 기동**. FR-004/SC-003 을 "컨텍스트당 1회(클래스 간 재사용)"로 현실화했다. 모듈당 단일 컨테이너가 정말 필요하면 **static 싱글턴 컨테이너**(JVM 1회 start, 미stop, Ryuk 정리)로 별도 최적화 — MVP 필수 아님.
- **CP-2 (컨텍스트 캐시 파편화 함정)**: 테스트별 `@SpringBootTest(properties=...)` 편차가 있으면 **각기 다른 컨텍스트로 캐시되어 컨테이너가 늘어난다.** 실제로 `FoodRepositoryAdapterTest` 가 `hibernate.generate_statistics=true` 를 달고 있어 이 테스트만 별도 컨텍스트/컨테이너가 된다. 컨테이너 수를 줄이려면 이런 프로퍼티 편차를 통일해야 한다(T016).
- **CP-3 (오해 방지)**: 한 실행 내 재사용은 **컨텍스트 캐싱이 자동 제공**(설정 0)한다. `.withReuse(true)`/`testcontainers.reuse.enable` 은 **빌드 간(여러 `./gradlew test`) 재사용**용 로컬 옵트인일 뿐 FR-004 달성 수단이 아니다. quickstart 의 "선택적 로컬 가속"으로만 유지.
- **CP-4 (공유 방식 정공법)**: 문서는 여러 클래스 공유에 **`@ImportTestcontainers`(컨테이너를 인터페이스에 정의) 또는 `@TestConfiguration`+`@Import`** 를 권장하고, **추상 베이스 클래스는 권장하지 않는다.** 따라서 D-4 의 Kotest 베이스(`MySqlIntegrationSpec`)는 **컨테이너 생명주기를 들지 않고**, 오직 `SpringExtension` 등록 + `@Import(MySqlContainerConfig)` 를 얹는 **얇은 Kotest 어댑터**로 한정한다. 컨테이너 정의·연결은 `@Bean @ServiceConnection`(문서 "Safe" 패턴)이 소유.
- **CP-5 (튜토리얼 함정 회피)**: `@Testcontainers`+static `@Container` 는 클래스 종료 시 컨테이너를 stop 하는데, Spring 이 컨텍스트를 캐시로 살려두면 **죽은 컨테이너 참조로 later 테스트가 깨진다**("later tests or bean destruction callbacks may fail"). Kotest+`@Bean` 경로라 애초에 이 방식을 쓰지 않는다 — 인터넷 예제 따라가다 새지 않도록 주의.

## 구현 결과 (실측)

- **마이그레이션 첫 실전 적용 — 클린 통과.** 전체 Flyway 체인(ingredient 생성→시드→drop→jsonify→replace)이 MySQL 8.4 컨테이너에 오류 없이 적용됐고, `ddl-auto=validate` 도 통과 — **엔티티↔마이그레이션 스키마 드리프트 0건**. T008 의 우려(리스크 1·2)는 현실화되지 않았다.
- **실제로 잡힌 H2-ism**: `FoodTestSeed` 가 JSON 컬럼 삽입에 H2 전용 `'…' FORMAT JSON` 구문을 쓰고 있었다 → MySQL 에선 무효라 제거(문자열 리터럴을 JSON 컬럼에 직접 삽입). 이것이 SC-004 가 노린 대표적 H2 잔재.
- **Testcontainers 2.0 좌표 변경(중요)**: Spring Boot 4.1 BOM 이 Testcontainers 를 **2.0.5** 로 고정하는데, 2.0 부터 DB 모듈 좌표가 `org.testcontainers:mysql` → **`org.testcontainers:testcontainers-mysql`** 로 바뀌었다(구 좌표는 1.21.x 까지만 존재). `org.testcontainers.containers.MySQLContainer` 클래스 경로는 호환 유지.
- **dependency-management 사각지대**: `io.spring.dependency-management` 가 `testFixtures*` 구성엔 BOM 버전을 적용하지 않아 `testcontainers-mysql` 이 버전 미해석(빈 버전)으로 실패 → 해당 구성에 `platform(spring-boot-dependencies)` 를 직접 얹어 해결.
- **컨테이너 기동 수(실측)**: 2개 모듈 JVM 에서 총 5개(app:api 3 컨텍스트 + infra:persistence 2 컨텍스트) — CP-1 대로 "컨텍스트당 1개". app:api 의 ~10개 MockMvc 클래스는 **단일 컨텍스트/컨테이너를 공유**(재사용 정상 동작). 모듈당 단일 컨테이너로 더 줄이는 건 별도 최적화로 보류(정당한 컨텍스트 분리 — MockMvc/마이그레이션/`generate_statistics`).
- **최종 검증**: `:app:api:test`(53) + `:infra:persistence:test`(23) = DB-backed 76 tests green, 전체 스위트 198 tests green(0 실패).
