# Research: KB-392 테스트 컨텍스트 통합

## R-1. 합성 애너테이션이 컨텍스트 캐시 키를 하나로 만드는가

- **Decision**: 모듈별 합성 애너테이션 — api `com.kbap.api.IntegrationTest`(= `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(MySql, Redis, FakeSocialTokenVerifierConfig, FakePlaceSearchConfig)`), batch `com.kbap.batch.BatchIntegrationTest`(= `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(MySql, SlowJobTestConfig)`). 각 모듈 test 소스셋에 둔다(페이크가 모듈 소속이라 testFixtures 로 올리지 않는다).
- **Rationale**: Spring TestContext 의 캐시 키는 병합된 `MergedContextConfiguration`(부트 클래스·`classes`·`properties`·`contextCustomizers` — `@Import` 집합·MockMvc 자동구성 포함)이다. 세 애너테이션 모두 메타 애너테이션 사용을 공식 지원한다: `@SpringBootTest` 는 `SpringBootTestContextBootstrapper` 가 `MergedAnnotations` 로 찾고, `@AutoConfigureMockMvc` 는 `@ImportAutoConfiguration` 기반으로 병합 탐색되며, `@Import` 는 `ImportsContextCustomizerFactory` 가 `findMergedAnnotation` 으로 감지한 뒤 테스트 클래스를 설정 클래스로 등록해 `ConfigurationClassParser` 가 메타 `@Import` 까지 수집한다. 헤더가 같으면 키가 같다 — 76개가 한 컨텍스트.
- **검증**: 첫 Red/Green 에서 클래스 두 개를 바꿔 같은 컨텍스트를 쓰는지(컨테이너 1개) 확인한 뒤 나머지를 치환한다.
- **Alternatives**: 76개 헤더를 동일한 3줄로 손 통일 → 다음 사람이 한 줄 빼먹으면 조합이 다시 늘어남 → 기각. 추상 베이스 클래스 → Kotest `BehaviorSpec()` 상속 구조와 충돌, 애너테이션이 더 가벼움 → 기각.

## R-2. 소셜 인증 페이크 통일

- **Decision**: `FakeSocialTokenVerifier`(프로그래머블) 하나로 통일. `verify(idToken)` = 실패 주입이 있으면 던지고, 아니면 `SocialIdentity(GOOGLE, providerUserId = idToken, email = DEFAULT_EMAIL)`. `FakeSocialAccountDeleter` 는 그대로. `AuthControllerTest.kt` 바닥의 두 페이크 + `@TestConfiguration` 을 `api/src/test/kotlin/com/kbap/api/auth/FakeSocialTokenVerifierConfig.kt` 로 옮긴다(같은 패키지 — import 변화 없음). `scenario/ScenarioSocialTokenVerifierConfig.kt` 삭제.
- **Rationale**: 시나리오 4개는 여정마다 `scenario-<접두어>-<uuid>` 토큰으로 서로 다른 회원이 필요하고(토큰 = 식별자), 인증/회원 테스트 3개는 고정 식별자 `google-sub-fixed` 와 이메일 `user@gmail.com` 을 단언한다. "토큰 = 식별자, 이메일 상수" 로 두면 시나리오는 무변경, 인증/회원 테스트는 로그인 헬퍼의 기본 토큰을 `"valid-token"` → `FakeSocialTokenVerifier.DEFAULT_SUB` 로 바꾸는 한 줄로 기존 단언이 유지된다(시나리오는 email 을 단언하지 않는다 — 확인).
- **Alternatives**: 페이크에 `identity` 람다를 주입해 클래스별로 바꿈 → 상태가 클래스 간에 새고 두 동작을 유지하는 복잡성 → 기각.

## R-3. 프로퍼티 변형 2개

- **Decision**: `FoodServiceTest` 의 `spring.jpa.properties.hibernate.generate_statistics=true` 를 `api/src/test/resources/application.yml` 전역으로 옮기고 헤더를 `@IntegrationTest` 로. `StructuredConsoleLoggingTest`(`logging.structured.format.console=ecs`) 는 콘솔 인코더 전체를 바꾸므로 그대로 두어 api 의 두 번째 컨텍스트로 남긴다.
- **Rationale**: Hibernate 통계는 수집만 켜는 설정이라 전역이어도 다른 테스트에 영향이 없다. ecs 는 전 테스트 로그 형식을 바꾸는 부작용이 있어 격리가 정당하다.
- **후속(2026-08-30)**: ecs 컨텍스트도 없앴다 — `StructuredConsoleLoggingTest` 를 Spring 없이 `StructuredLogEncoder` 직접 구성 + staging/prod yml 단언으로 재작성해 api 컨텍스트 1. 최소 `SpringApplication` 프로브는 `LogbackLoggingSystem` 의 JVM 1회 초기화 때문에 프로퍼티가 반영되지 않아 기각.

## R-4. common 부트 클래스 단일화

- **Decision**: `common/src/test/kotlin/com/kbap/common/CommonTestApp.kt` — `@SpringBootConfiguration @EnableAutoConfiguration @AutoConfigurationPackage(basePackages = ["com.kbap.common.domain"])`. 11개 테스트는 전부 `@SpringBootTest` + `@Import(MySqlContainerConfig::class)` 로 통일(`classes = […]` 제거 — 패키지 상향 탐색으로 `CommonTestApp` 을 찾는다). `*TestApp` 7개 삭제.
- **Rationale**: common 의 `@Component` 류는 `infra.llm` 구성뿐이라 도메인 패키지만 스캔하면 외부 키 없이 뜬다(`AutoConfigurationPackage` 가 엔티티·리포지토리 스캔 루트). 기존 `BlockTestApp`·`ReviewTestApp` 이 같은 방식으로 이미 동작한다. 헤더를 통일해야 캐시 키가 같아진다.

## R-5. batch 통일

- **Decision**: `SlowJobTestConfig` 를 `batch/src/test/kotlin/com/kbap/batch/trigger/SlowJobTestConfig.kt` 로 분리하고 `@BatchIntegrationTest` 가 항상 포함. 6개 헤더 치환 → 컨텍스트 1.
- **Rationale**: 느린 잡은 잡 목록에 항목 하나를 더할 뿐이고 잡 수·목록을 단언하는 테스트는 없다(확인). batch 는 web(트리거 컨트롤러)이 있어 MockMvc 자동구성이 전 컨텍스트에 무해하다.

## R-6. 한 DB 를 공유하는 클래스 증가 대응

- **Decision**: 선제 정리 코드를 넣지 않는다. `./gradlew clean build` 2회(두 번째는 `--rerun-tasks`)로 순서 의존을 드러내고, 실패한 클래스에만 `beforeSpec`/`afterSpec` 정리를 추가한다. 프로그래머블 페이크 상태는 이미 `beforeSpec` 에서 `reset()` 한다.
- **Rationale**: 44개가 이미 한 DB 를 공유해 왔고, 스펙 FR-009(본문 무변경)를 지키려면 실패가 증명된 곳만 손댄다.

## R-7. 측정

- **Decision**: 컨텍스트 수 = 실행 중 MySQL 컨테이너 수(`docker ps --filter ancestor=mysql:8.4`). KB-391 이전이라 컨테이너가 컨텍스트마다 뜨는 점을 측정에 이용한다.

## R-8. 문서

- **Decision**: `CLAUDE.md` 테스트 절에 규칙 추가, `../kbap-agenthub/wiki/test-context-consolidation.md` 신설 + `INDEX.md`. ADR 없음.
