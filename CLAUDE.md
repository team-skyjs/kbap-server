# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 하네스: SpecKit·TDD 개발 팀

**목표:** 헌법 원칙 I(Test-First)을 강제하며 test-writer→implementer→(code-reviewer∥database-expert) 에이전트 팀으로 task 를 Red→Green→Refactor→리뷰까지 구동한다.

**트리거:** 기능/task 를 TDD 로 구현하거나(예: "TDD 로 구현해", "tasks.md 진행", "US2 구현", "테스트부터 짜고 구현"), 구현 결과를 검토/재실행/부분 보완할 때 `tdd-harness-orchestrator` 스킬을 사용하라. 단순 질문·단발 수정은 직접 응답 가능. (에이전트: `.claude/agents/`, 스킬: `.claude/skills/`.)

**변경 이력:**
| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-06-28 | 초기 구성 — test-writer·implementer·code-reviewer·database-expert 4에이전트 + 5스킬(역할 4 + 오케스트레이터 1) | 전체 | SpecKit·TDD 역할 분담 자동화 요청 |

## 개요

`kbap/kbap-server` — Kotlin으로 작성된 Spring Boot 백엔드. Gradle 멀티모듈 **모듈러 모놀리스**다. 모듈 접두어(`kbap-`)는 쓰지 않고 그룹 컨테이너(`domain/`·`infra/`·`app/`)로 분류한다. **실행 가능한 bootJar 는 두 개** — `:app:api`(web, 진입점 `com.kbap.KbapApiApplication`)와 `:app:batch`(배치, 진입점 `com.kbap.app.batch.KbapBatchApplication`)다.

**2026-07-14 아키텍처 대개편** 이후 구조: JPA 엔티티가 곧 도메인 모델이고(별도 도메인 모델·toDomain/from 변환 없음), **비즈니스 로직은 도메인 모듈의 도메인 서비스가 소유**한다. 도메인 모듈끼리는 **필요한 의존을 build.gradle 에 단방향 선언**해 서로의 도메인 서비스를 조합한다(Gradle 이 순환을 컴파일 차단). `:application` 은 **무소속 유스케이스(Home·Auth)와 도메인 간 순환 해소용 `~ApplicationService`** 만 두는 얇은 조합 계층이다. 리포지토리는 `internal` — 유일 창구는 도메인 서비스(컴파일러 강제). 외부 시스템(jjwt·firebase·Redis·LLM)은 **인터페이스는 소비 계층에, 구현은 `:infra:*` 에, 조립은 부트앱 config** 패턴으로 격리한다. DB 는 공유(batch `flyway off`, 스키마 owner=api). 공통 빌드 설정은 `buildSrc` 컨벤션 플러그인에 둔다.

### 모듈 구조

의존 방향은 단방향으로 고정한다(Gradle 이 순환을 컴파일 차단).

```
:core ← :domain:avoidance ← :domain:member ← :domain:food ← :domain:scan
              (도메인 간 단방향 의존 — 서로의 도메인 서비스를 조합)
                                   ▲
        :application (조합 계층: Home·Auth ApplicationService + seam 인터페이스·dto)
                                   ▲
        :app:api (web bootJar, Flyway owner — 컨트롤러가 도메인 서비스 직접 호출)
        :app:batch (배치 bootJar, 도메인 레포·서비스를 @Import 조립, flyway off)

:infra:llm / :infra:auth / :infra:redis — 외부 시스템 구현체(seam 인터페이스의 구현), 부트앱이 조립
```

- `:app:api`: web bootJar — controller, API DTO(`*Request`/`*Response`), `config/`(빈 조립 `@Configuration` — CORS·OpenAPI·Auth). 진입점 `KbapApiApplication`은 `com.kbap` 루트(스캔·AutoConfigurationPackages 가 전 계층 커버). **컨트롤러는 도메인 서비스를 직접 호출**하고, home·auth 만 `:application` 을 경유한다. DB 마이그레이션(Flyway) **스키마 owner** — `src/main/resources/db/migration`. 패키지 `com.kbap.app.api`.
- `:application`: **얇은 조합 계층** — 무소속 유스케이스(`HomeApplicationService`·`AuthApplicationService`)와 향후 도메인 간 순환 해소용 `~ApplicationService`, 그리고 auth seam 인터페이스(`TokenIssuer`·`TokenParser`·`SocialTokenVerifier`·`RefreshTokenStore`)와 dto 를 둔다. 도메인 간 순환이 실제로 발생할 때만 여기로 조합을 승격한다. 패키지 `com.kbap.application`.
- 도메인 컨텍스트 모듈: `domain/` 컨테이너 직속 — active: `:domain:{food,member,scan,avoidance,research}`, deferred placeholder: `:domain:review`. 각 도메인 = **JPA 엔티티(=도메인 모델, 도메인 메서드 내장)** + **도메인 서비스(비즈니스 로직 소유, `@Service` + `internal constructor`)** + 리포지토리(`internal`) + `dto/`. 도메인 간 의존은 필요한 것만 단방향 선언 — 현재 `avoidance ← member ← food ← scan`. **가드레일: member 는 리프 유지** — member 가 타 도메인 서비스를 필요로 하는 순간 그 조합을 `:application` 으로 승격한다. 패키지 `com.kbap.domain.<도메인>`. (`research`는 순수 로직·배치 전용 — 영속 없음, web 미노출.)
- `:core`: 공통 타입·**통합 에러**(`ErrorCode` enum + `BusinessException`)·유틸·`RiskLevel`, 외부 client **seam 인터페이스**(`ScannedNameInterpreter`), **여러 도메인이 공유하는 vocabulary**(`LanguageCode`), 그리고 **영속 공통**(`BaseEntity`·`EntityStatus` — jakarta/hibernate 는 `compileOnly`, 런타임 제공은 도메인 모듈). 특정 컨텍스트가 소유하는 코드는 **소유 컨텍스트 모듈**에 두고 타 컨텍스트는 코드로만 참조한다(원칙 II). 애플리케이션 코드는 Spring-free. 패키지 `com.kbap.core`.
- `:infra:llm`: LLM 호출 어댑터(Spring AI — ADR-0010). seam 은 `:core` 의 `ScannedNameInterpreter`. `:app:batch`가 `implementation`, `:app:api`가 `runtimeOnly` 로 의존. (조립 `@Configuration` 은 api·batch 양쪽이 쓰므로 예외적으로 모듈 안에 둔다.)
- `:infra:auth`: 인증 구현 어댑터 — jjwt(`JwtTokenIssuer`/`JwtTokenParser`) + firebase-admin(`Firebase*` + `FirebaseSocialAuth` 팩토리). seam 은 `:application`(`TokenIssuer`·`TokenParser`·`SocialTokenVerifier`)과 `:domain:member`(`SocialAccountDeleter`). `:app:api` 가 `implementation`(config 조립).
- `:infra:redis`: Redis 어댑터 — `RedisRefreshTokenStore`. seam 은 `:application`(`RefreshTokenStore`). `:app:api` 가 `runtimeOnly`.
- `:app:batch`: 배치 bootJar. **컴포넌트 스캔을 자신 + `com.kbap.infra.llm` 로 좁힌다** — 도메인 서비스 그래프(외부 seam 필요)를 올리지 않고, 필요한 도메인 창구(`FoodScoringSource`·`AvoidanceCatalogService`)만 `@Import` 로 조립한다. 엔티티/레포 스캔은 `@AutoConfigurationPackage("com.kbap")`. **flyway off**(스키마 owner=api). 패키지 `com.kbap.app.batch`.

각 모듈은 `src/main`·`src/test` 소스셋을 모두 가진다. 도메인 모듈 간 의존과 `:core`·data-jpa 는 `api`(엔티티가 서비스 시그니처에 노출), 그 외 모듈 간 의존은 `implementation` 기본. 경계 강제 수단은 세 겹이다 — **Kotlin `internal`**(리포지토리는 소유 도메인 서비스만), **Gradle**(도메인 간 순환 컴파일 차단), **ArchUnit**(`ModuleBoundaryTest.kt`, Kotest 태그 `arch` — core Spring-free·도메인→상위 계층(application/infra/app) 금지·`@Entity` 는 도메인 모듈에만·컨트롤러 매핑 `/api/v` 시작·application→infra/app 금지).

## 설계 / 문서 위치

- **백엔드 아키텍처**(DDD·바운디드 컨텍스트·모듈 구성·데이터/AI 파이프라인) → [`docs/architecture/`](docs/architecture/). 강제 규칙은 `docs/architecture/kbap-conventions.md`.
- **의사결정 기록(ADR)** → [`docs/adr/`](docs/adr/). SpecKit 사이클마다 중요한 결정을 남긴다.
- **구현 설계**(기능별 "어떻게") → SpecKit `specs/NNN-slug/`(spec·plan·tasks). 교차-컨텍스트 흐름은 `mermaid-flows` 스킬로 시퀀스 다이어그램을 그린다.
- **제품 개요·기획 PRD("무엇을/왜")** → 공유 허브 `agent-hub/`(이 repo에선 git-ignored, 별도 서브모듈로 관리). 구현 세부는 여기 두지 않는다.

## 기술 스택

- **Kotlin 2.3** / **JVM (Java 21 toolchain)** — Gradle toolchain이 JDK를 해석하므로 로컬 `JAVA_HOME`에 묶이지 않는다(`settings.gradle.kts`의 foojay-resolver가 자동 프로비저닝).
- **Spring Boot 4.1** — web/validation/actuator/data-jpa/data-redis 스타터. 영속: **MySQL**(prod, `mysql-connector-j`, 통합 테스트는 MySQL Testcontainers) + **Redis**(refresh token — KB-118). DB 마이그레이션: **Flyway**(+flyway-mysql). API 문서: **springdoc-openapi**(Swagger UI). 인증: 자체 JWT(jjwt) + Firebase ID 토큰 검증(firebase-admin) — 구현은 `:infra:auth`, refresh token 저장은 `:infra:redis`.
- **LLM: Spring AI 2.0**(Boot 4 호환 라인) — 전용 모듈 **`:infra:llm`**(ADR-0010)에 `spring-ai-starter-model-openai` + `spring-ai-starter-model-google-genai`. 공개 API `LlmFanoutClient`·값타입·구성이 이 모듈에 응집되고 **`:app:batch`가 `implementation`으로 직접 의존**해 잡에서 호출(단일 소비자=배치, `:core` port·`runtimeOnly` 조립은 생략 — web/application 재사용 시 kernel port 승격 후속). 3개 모델(OpenAI·Upstage·Gemini)을 `kbap.llm.*` 프로퍼티 + `@ConditionalOnProperty`로 명시 구성: Upstage는 OpenAI 호환이라 openai 스타터를 base-url만 교체해 재사용, Gemini는 google-genai 스타터(API 키 방식). 키/활성 플래그가 없으면 caller 빈이 미생성돼 batch/web 부팅이 안전하며, Spring AI 자동구성 유입은 `application.yml`의 `spring.ai.model.*=none`으로 차단. fan-out은 JDK21 가상스레드 + `CompletableFuture`, 단일모델 seam `LlmModelCaller`로 부분실패를 페이크 단위검증(헌법 I).
- 빌드 도구: **Gradle (Kotlin DSL)**, 래퍼 사용.
- 테스트: **JUnit 5 플랫폼**(`useJUnitPlatform`) + **Kotest**(`kotest-runner-junit5` + `kotest-assertions-core`). Spring 모듈은 `spring-boot-starter-test`도 추가.

### 빌드 구성 (버전·공통 설정 관리)

> 입문 설명서: [`docs/guides/gradle-made-easy.md`](docs/guides/gradle-made-easy.md).

- **버전 카탈로그** `gradle/libs.versions.toml`이 모든 버전(라이브러리·플러그인)의 단일 출처다. `libs.*` 접근자로 참조한다. 스타터 버전은 대부분 Spring Boot BOM이 관리하므로 카탈로그에 버전을 적지 않는다.
- **공통 설정은 `buildSrc` 의 컨벤션 플러그인**(미리 컴파일된 `kbap.*.gradle.kts`)에 둔다. 각 모듈은 `plugins { id("kbap.<archetype>") }` 한 줄로 자기 아키타입을 선언한다. 루트 `build.gradle.kts`는 거의 비어 있다(집계 전용).
  - `kbap.kotlin-common` — **모든 leaf 모듈** 공통: kotlin-jvm·java-library, Java 21 toolchain, Kotlin 엄격성 플래그, `group`/`version`, 공통 테스트(Kotest + JUnit launcher + `useJUnitPlatform()` + `-Dkotest.tags` 전달). Spring-free 모듈은 이것만 적용. `:core` 는 여기에 dependency-management + jakarta/hibernate `compileOnly` + testFixtures(Testcontainers 공통 설정)를 얹는다.
  - `kbap.spring-conventions` — **Spring 라이브러리 공통**(core 제외): kotlin-common 위에 kotlin-spring·dependency-management·Spring Boot/AI BOM·`kotlin-reflect`/`jackson-module-kotlin`/`spring-boot-starter-test`를 얹는다.
  - `kbap.spring-boot-application` — **부트 앱(bootJar)**: `:app:api`, `:app:batch`. spring-conventions 위에 `org.springframework.boot`.
  - `kbap.domain-conventions` — **도메인 컨텍스트 공통**(food/member/scan/avoidance/research + review placeholder): kotlin-common 위에 kotlin-spring·**kotlin-jpa(no-arg)**·dependency-management·Boot BOM·`api(:core)`·`api(data-jpa)`·`implementation(kotlin-reflect·jackson-module-kotlin)`·`runtimeOnly(mysql)`·테스트 공통(starter-test·kotest-extensions-spring·`testFixtures(:core)`)을 얹는다(영속이 도메인 안으로 들어온 결과). 도메인 간 의존 등 모듈 고유 의존만 각 build 파일에 둔다.
- **모듈별 고유 설정만 각 모듈 `build.gradle.kts`** 에 둔다(app:api=web/validation/actuator/flyway/springdoc+application 의존, infra:llm=spring-ai, app:batch=필요 도메인·llm 의존 등). 모듈 build 파일에서 의존성은 **문자열 표기**(`"implementation"(...)`)로 적는다(플러그인이 컨벤션에서 적용돼 타입 안전 단축표기 미생성). 라이브러리 좌표는 모듈 build 파일에선 `libs.*`로 정상 사용.
- **버전 카탈로그 접근**: 컨벤션 플러그인 **안에서는** `libs.*` 타입세이프 접근자가 안 잡혀 `VersionCatalogsExtension`의 `findLibrary`/`findVersion`으로 조회한다. buildSrc 는 `buildSrc/settings.gradle.kts`에서 루트 `gradle/libs.versions.toml`을 `libs`로 가져오고, `buildSrc/build.gradle.kts`는 `libs.plugins.*`를 플러그인 마커 좌표로 변환해 서드파티 Gradle 플러그인을 classpath 에 올린다.
- **트레이드오프**: buildSrc 변경 시 전체 빌드 캐시가 무효화돼 느려질 수 있다(대신 도메인 5종 dedup·모듈 파일 슬림).

## 명령어

모든 작업은 Gradle 래퍼(`./gradlew`)로 실행한다.

```bash
./gradlew build                          # 전체 모듈 컴파일 + 테스트 + 아티팩트 생성
./gradlew :app:api:bootRun         # web 앱 실행 (프로필은 SPRING_PROFILES_ACTIVE 로 지정)
./gradlew :app:batch:bootRun           # 배치 앱 실행
./gradlew test                           # 전체 모듈 테스트 실행
./gradlew :app:api:test            # 특정 모듈만 테스트
./gradlew :app:api:test --tests "com.kbap.app.api.KbapApiApplicationTests"          # 단일 테스트 클래스
./gradlew clean                          # 빌드 산출물 정리
```

실행 프로필은 `local`/`dev`/`staging`/`prod` 4종이다(`SPRING_PROFILES_ACTIVE`로 선택). 통합 테스트(`@SpringBootTest`)는 MySQL Testcontainers(`:core` testFixtures 의 `MySqlContainerConfig`, `@ServiceConnection`)로 동작한다(KB-46 — H2 미사용). ArchUnit 스펙만 제외하려면 `-Dkotest.tags="!arch"`.

별도 lint 태스크는 설정되어 있지 않으며, Kotlin null-safety 엄격성은 컴파일 단계에서 강제된다(아래 참고).

## 폴더별 지침 (CLAUDE.md) 운영

- 특정 폴더에 **그 폴더 작업 시 꼭 지켜야 할 규칙/관례**(코드만 봐선 알 수 없고, 일관되게 강제돼야 하는 것)가 있다고 판단되면, **그 폴더에 `CLAUDE.md`를 만들지 사용자에게 먼저 물어본다.** 임의로 만들지 않는다.
- 이유: 하위 폴더의 `CLAUDE.md`는 그 하위 트리의 파일을 다룰 때 자동 로드되므로, 규칙을 "가장 가까운 곳"에 두면 매번 확실히 적용되고 노이즈도 없다.
- 규칙은 가장 좁은 적용 범위의 폴더에 둔다(예: PRD 전용 규칙 → `agent-hub/prd/CLAUDE.md`). 상세 템플릿/레퍼런스는 같은 폴더의 다른 문서로 분리하고 `CLAUDE.md`엔 핵심 규칙+포인터만 둔다.

## 디렉터리 생성 규칙

- **디렉터리가 (다른 파일 없이) 빈 채로 남는 경우에만 `.gitkeep`을 추가한다.** (빈 디렉터리는 git이 추적하지 않으므로) 이미 파일이 있는 디렉터리에는 넣지 않는다.

## 컨벤션

- **Kotlin 소스 주석은 "코드로 표현 불가능한 제약"만 허용한다 (2026-07-14 완화).** 코드가 하는 일·다음 줄 설명·변경 정당화 주석은 여전히 금지 — 코드는 이름과 구조로 의도를 드러내는 **self-documenting** 이 기본이다. 단 **코드 자체로는 드러나지 않는 설계 제약**은 짧은 라인 주석으로 남긴다(예: "의도적 무트랜잭션 — 제약 위반 폴백이 세션을 무효화", "읽기 전용 매핑 — 쓰기는 리포지토리 직접", 스캔 제외 사유). KDoc·서사형 블록 주석은 금지. 긴 맥락(설계 근거·트레이드오프)은 커밋 메시지·`docs/`·ADR 에 남긴다. (빌드 스크립트·Flyway SQL·yml 주석은 규약 밖.)
- 소스는 각 모듈의 `src/main/kotlin/...`, 테스트는 `src/test/kotlin/...`에서 동일 구조로 미러링한다. **패키지는 모듈 경로를 미러링해 `com.kbap.<layer>` 로 둔다** — 커널 `com.kbap.core`(`core/`), 도메인 컨텍스트 `com.kbap.domain.<context>`(예: `domain/food/src/main/kotlin/com/kbap/domain/food/` — 루트에 도메인 서비스·리포지토리(`internal`)·seam 인터페이스, **도메인 모델(엔티티·값 객체·enum)은 `model/` 하위 패키지**, 경계 DTO 는 `dto/`), 조합 계층 `com.kbap.application`, web `com.kbap.app.api`(컨트롤러·DTO·`BaseResponse`·`config/`), 배치 `com.kbap.app.batch`, 인프라 `com.kbap.infra.<어댑터>`. **부트 진입점 `KbapApiApplication`은 패키지 루트 `com.kbap`** 에 두어 기본 컴포넌트 스캔·AutoConfigurationPackages 가 전 계층(엔티티·리포지토리 포함)을 커버한다(별도 `scanBasePackages` 불필요). 배치 진입점은 `com.kbap.app.batch` — 단 배치는 `scanBasePackages` 를 자신 + `com.kbap.infra.llm` 로 좁힌다(도메인 서비스 미탑재).
- web 실행 설정은 `app/api/src/main/resources/`에 YAML로 둔다: 베이스 `application.yml` + 프로필별 `application-{local,dev,staging,prod}.yml`. 확장자는 `.yml`로 통일한다(`.yaml` 아님). 테스트용 오버라이드는 `app/api/src/test/resources/application.yml`(Flyway off, Testcontainers MySQL 에 Hibernate `schema-generation=create`). 배치는 `app/batch/src/main/resources/application.yml`(flyway off). 로깅은 각 앱 `logback-spring.xml`이 Boot 기본(`base.xml`)을 include 한다.
- 컴파일러 엄격성 플래그는 `buildSrc`의 `kbap.kotlin-common` 컨벤션 플러그인에서 전 모듈에 일괄 적용되며, 신규 코드도 이를 준수해야 한다:
  - `-Xjsr305=strict` — JSR-305 nullability 애너테이션을 강제 제약으로 취급(Spring/Java API 호출 시 영향).
  - `-Xannotation-default-target=param-property` — Kotlin 프로퍼티의 기본 애너테이션 타깃을 변경.
- **테스트 스타일 (고정).** **모든 테스트는 Kotest `BehaviorSpec` 으로 통일**한다(다른 Spec 스타일·JUnit `@Test` 금지). 구조는 **`given("대상/전제") > \`when\`("상황") > then("기대 결과")`** 를 기본으로 하며, `given`·`` `when` ``·`then` 설명은 **한국어**로 쓴다(예: `` given("BoundingBox 생성") { `when`("x 가 음수이면") { then("예외를 던진다") { ... } } } ``).
  - **Spring 통합 테스트**(`@SpringBootTest`·MockMvc·repository)도 `BehaviorSpec` 으로 작성한다. `kotest-extensions-spring`(`io.kotest.extensions.spring.SpringExtension`)을 써서 클래스 본문 스타일(`class Foo : BehaviorSpec() { override fun extensions() = listOf(SpringExtension); @Autowired lateinit var ...; init { given... } }`)로 빈을 주입한다. `SpringExtension` 의존성은 `kbap.spring-conventions` 컨벤션 플러그인이 전 Spring 모듈 테스트에 일괄 제공한다.
  - MockMvc 는 `@AutoConfigureMockMvc` + `@Autowired MockMvc` 로 주입한다(`ObjectMapper` 빈은 주입 안 되므로 `jacksonObjectMapper()` 로 직접 생성).
- **서비스 메서드 네이밍 (고정 — 2026-07-14).** 서비스(도메인·애플리케이션) public 메서드는 다음 규칙을 따른다:
  - **단건 조회**: `get~` = 없으면 `BusinessException`(반환 non-null) / `find~` = 없으면 null(반환 `T?`). JPA `getReferenceById`/`findById` 구분과 동일한 계약.
  - **목록/페이지 조회**: `find~s`·`findAll~`·`findBy~`, 페이지 반환은 `~Page`. 파생 계산 결과라도 맨명사 금지 — 동사로 시작(`getAvoidedCodes`).
  - **CRUD**: 생성 `create~` · 수정 `update~` · 삭제 `delete~`(소프트).
  - **도메인 행위는 유비쿼터스 언어 동사 그대로** — `login`·`logout`·`withdraw`·`completeOnboarding`·`assessMenuBoard`·`search`·`increaseScanCount` 처럼 업무 용어를 CRUD 접두로 뭉개지 않는다(가장 우선하는 규칙).
  - **보조**: boolean `is~/has~/exists~`, 개수 `count~`, 순차 공급 `next~`.
- **크로스 도메인 엔티티 참조는 `Long` id 컬럼 (2026-07-14 개정).** 엔티티 간 참조는 원칙적으로 **id 값 컬럼(`Long`, 명확한 필드명 `memberId`/`foodId`)**으로 든다 — id 값 클래스(`FoodId`·`MemberId`)와 `IdConverter` 는 JPA 마찰(쿼리 파라미터 언랩·no-arg 보조 생성자) 대비 실이득이 없어 폐기했다. 예외적으로 **읽기 전용 연관**은 허용한다(현재 유일: `Food`→성분 `@OneToMany(EAGER)+@JoinColumn(insertable=false,updatable=false)+@BatchSize` — 쓰기는 레포지토리 직접). 외래키 제약은 코드가 아니라 **Flyway 스키마**가 강제한다(ON DELETE 없음 — 소프트 삭제 구조).
- **JPA 엔티티 작성 (고정).** **엔티티가 곧 도메인 모델**이다 — 도메인 메서드(`completeOnboarding`·`overallRisk` 등)를 엔티티에 두고, 별도 도메인 모델 클래스·`toDomain`/`from` 변환을 만들지 않는다. 값 객체(`MemberProfile`·`Ranking` 등)는 유지. **Spring Data Repository 는 `internal`** — 영속 접근의 public 창구는 **도메인 서비스**(`@Service` + `internal constructor`) 하나다(컴파일러 강제). 엔티티·값 객체·enum 은 도메인 모델 패키지 `com.kbap.domain.<도메인>.model` 에 둔다(서비스·리포지토리·seam 은 도메인 루트). **모든 엔티티는 `com.kbap.core.persistence.BaseEntity`(`@MappedSuperclass`)를 상속**한다 — `id`(IDENTITY)·`status`(`EntityStatus` ACTIVE/DELETED 소프트삭제)·`createdAt`·`updatedAt` 공통 제공, 엔티티엔 **자체 id·생성/수정 시각을 두지 않는다**(도메인 고유 상태는 `status` 와 컬럼명 분리 — 예: member 의 `member_status`). `kotlin-jpa`(no-arg)는 `kbap.domain-conventions` 가 전 도메인 모듈에 적용한다(전 필드 기본값이 있으면 no-arg 자동 생성). JPA 애너테이션은 **use-site 타깃 없이**(`@Id`/`@Column`) 단다.
  - **컬럼 정의는 MySQL 기준으로 고정한다 (H2 호환은 고려하지 않는다).** 문자열 컬럼은 `@Column(length = N)` 으로 길이를 명시하고(예: `length = 20`), 길이 없는 `columnDefinition = "VARCHAR"` 같은 비-MySQL 형식은 쓰지 않는다. 엔티티 컬럼 길이는 Flyway 마이그레이션과 일치시킨다.
  - **소프트 삭제는 BaseEntity 가 `@SQLRestriction("status = 'ACTIVE'")` 로 상시 적용**한다(@MappedSuperclass 에서 전 엔티티로 상속). 따라서 모든 조회는 자동으로 `ACTIVE` 만 본다 — 리포지토리 쿼리에 별도 status 조건을 달지 않는다. 삭제는 row 제거가 아니라 `BaseEntity.delete()`(status=DELETED).
- **트랜잭션 경계 (고정 — 2026-07-14).** DB 를 만지는 서비스 public 메서드는 **전부 명시적 `@Transactional`**(읽기는 `readOnly = true`)을 선언한다 — 리포지토리 기본 트랜잭션에 암묵 의존 금지. 상태 변경은 관리 엔티티 dirty checking(불필요한 `save()` 호출 금지). 애플리케이션 서비스는 여러 조회를 한 스냅샷으로 묶을 때만 선언. **외부 시스템 호출은 트랜잭션 밖**(예: 탈퇴의 소셜 삭제는 `AuthApplicationService` 가 선행). 예외는 주석으로 사유 명시(예: `findOrSignUp` — unique 제약 위반 폴백이 세션을 무효화해 단일 트랜잭션 불가).
- **Flyway 마이그레이션 버전 규칙 (고정).** 마이그레이션 버전은 **점 구분 timestamp** `Vyyyy.MM.dd.HH.mm.ss__description.sql` 로 짓는다 — 값은 **파일 생성 시점의 로컬 현재 시각**(각 파트 두 자리 zero-pad), 예: `V2026.07.05.14.30.12__add_review_table.sql`. 병렬 브랜치에서 각자 다음 정수를 잡을 때 생기는 버전 번호 머지 충돌을 없애기 위함이다(Flyway 공식 유효 포맷 — 예시 `2013.01.15.11.35.56`). Flyway 는 버전을 숫자 파트열로 정렬한다. 기존 정수 마이그레이션(`V1`~`V10`)은 **로컬 DB 전용·프로덕션 이전 단계에서 커밋 시각 기준 timestamp 로 일괄 전환**했으므로(KB-44) 현재 모든 마이그레이션이 timestamp 포맷이다. 생성 시각 기반이라 먼저 만들고 늦게 머지된 과거 버전이 out-of-order 로 적용될 수 있어 **`spring.flyway.out-of-order=true`**(베이스 `application.yml`)를 켜 두며, 그 전제로 **각 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않게 독립적으로 작성**한다. **금지 사례**: (1) 신규에 정수 버전(`V11`) 사용, (2) **공유/프로덕션 DB 에 이미 적용된** 마이그레이션 파일 수정·리네임(checksum/history 파손 — 일괄 전환은 로컬 전용·프로덕션 이전 단계에서만 가능), (3) 순서 의존 마이그레이션 작성. (상세·근거: [`docs/architecture/kbap-conventions.md`](docs/architecture/kbap-conventions.md) "Flyway 마이그레이션 버전 규칙".)
  - **시드-동기화 테스트 ↔ 마이그레이션 파일명 결합 (주의).** 일부 테스트는 마이그레이션 SQL 을 **리소스 경로로 하드코딩해 읽는다**(예: `AvoidanceCatalogSeedSyncTest` 의 `seedResourcePath = "db/migration/…​.sql"`). 시드가 담긴 마이그레이션의 **파일명(버전)·위치를 바꾸면 그 테스트의 참조도 반드시 함께 갱신**한다 — 파일을 못 찾으면 내용이 빈 문자열로 읽혀 "파일 없음"이 아니라 **데이터 불일치 assertion 실패**로 조용히 깨진다(오진 주의). 테스트 설명(`given(...)`)에는 버전 번호를 박지 말고 버전 비의존 문구를 쓴다.

### API 응답 규약 (고정)

**모든 컨트롤러 응답 타입은 `ResponseEntity<BaseResponse<T>>`로 고정한다.** 예외 없이 모든 API는 아래 공통 봉투로 감싸 반환한다. (봉투 클래스명은 Swagger `@ApiResponse`·`ResponseEntity` 와의 혼동을 피하려고 `BaseResponse` 로 두며, 페이로드 필드는 `payload` 다.)

```kotlin
data class BaseResponse<T>(
    val success: Boolean,
    val payload: T? = null,
    val message: String? = null,
    val code: String? = null,
)
```

- **성공**: `BaseResponse.ok(payload)` — `success=true`, `payload`에 페이로드.
- **실패**: `BaseResponse.fail(code, message, payload?)` — `success=false` + **`code`(클라이언트 분기용 안정 식별자)** + `message`(표시용) + 선택적 `payload`(후속 동작용 구조화 데이터).
- **에러 코드 체계 (고정)**: `ErrorCode` enum(`:core`) 단일 출처 — `code` 는 **도메인 접두 + 3자리 채번**(`COMMON-001`·`AUTH-004`·`MEMBER-003`·`FOOD-001`). `KB-` 접두는 Jira 이슈 키와 충돌하므로 금지. 클라이언트는 `code` 로만 분기하고 `message` 매칭은 금지(문구는 자유 변경). 예: access 만료 `AUTH-004` → refresh 호출, refresh 만료 `AUTH-006` → 재로그인. 형식·유일성은 `ErrorCodeStatusTest` 가 강제. 예외는 `BusinessException(errorCode, payload = null)` 하나 — 도메인별 예외 클래스를 만들지 않는다.
- 컨트롤러는 raw 도메인/DTO 를 직접 반환하지 않고 항상 `ResponseEntity<BaseResponse<T>>`로 감싼다. HTTP 상태코드는 `ResponseEntity`로, 비즈니스 성공/실패 플래그는 `BaseResponse.success`로 표현한다.
- `BaseResponse`는 모든 web 응답이 공유하므로 `:app:api` 에 둔다. 페이로드 타입 `T`는 각 API 의 응답 DTO 다.

### API 엔드포인트 경로 규약 (고정)

**모든 컨트롤러 경로는 `/api/{버전}` 으로 시작한다.** 예외 없이 버전 prefix 와 함께 노출한다(예: `POST /api/v1/scans`, `GET /api/v1/foods/detail`).

- 버전 베이스는 `com.kbap.app.api.common.ApiPaths` 의 상수로 **단일 출처** 관리한다(`const val V1 = "/api/v1"`). 컨트롤러는 이 상수에 리소스 경로만 이어 붙인다 — `@RequestMapping(ApiPaths.V1 + "/scans")`. 경로 문자열에 `/api/v1` 을 직접 하드코딩하지 않는다.
- 새 버전 도입 시 `ApiPaths` 에 상수 추가(예: `const val V2 = "/api/v2"`)하고 해당 버전 컨트롤러가 참조한다. 같은 리소스의 v1·v2 컨트롤러는 서로 다른 베이스를 써 **공존**한다(기존 버전 경로는 깨지 않는다).
- 이 규약은 **비즈니스 API(`com.kbap.app.api` 컨트롤러)** 에만 적용한다. actuator·springdoc(Swagger UI) 등 프레임워크 경로는 규약 밖이며 자체 경로를 유지한다.
- 경계 강제는 ArchUnit(`ModuleBoundaryTest`)이 담당 — 모든 컨트롤러 매핑이 `/api/v` 로 시작하는지 검증한다.

### 파라미터 애너테이션 위치 (고정)

**Spring 애너테이션은 전부 구현 컨트롤러 클래스에, swagger 문서 애너테이션만 `*Api` 인터페이스에 둔다.** 컨트롤러: 매핑(`@GetMapping`·`@PostMapping` 등), web 바인딩(`@RequestBody`·`@PathVariable`·`@RequestParam`·`@Valid`), 인증 리졸버(`@AuthMemberId`·`@AuthMemberIdOrNull`). 인터페이스: swagger 문서(`@Tag`·`@Operation`·`@Parameter`·`@ApiResponses`·`@SecurityRequirement`·`@io.swagger...RequestBody`)만. Spring 은 인터페이스 선언도 병합해 해석하지만(HandlerMethodParameter), 개발자가 컨트롤러 파일만 열어 그 엔드포인트의 경로·바인딩·인증 방식을 즉시 파악할 수 있어야 한다 — 인터페이스에만 두면 컨트롤러에선 평범한 파라미터로 보여 오독한다. 인터페이스 쪽 파라미터는 애너테이션 없이 타입만 맞춘다(중복 선언 금지 — 두 곳이 어긋나면 어느 쪽이 진실인지 모호해진다). swagger 문서 노출은 `OpenApiConfig` 의 `SpringDocUtils.addAnnotationsToIgnore` 가 인증 애너테이션 두 개를 숨기므로 `@Parameter(hidden = true)` 를 따로 달지 않는다.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/kb-130-api-request-logging/plan.md` (KB-130 API 요청 흐름 로깅 — 비즈니스 API(`/api/*`) 요청마다 UUID v4 상관 키를 부여해 그 요청의 모든 로그(진입·처리·에러·응답)에 자동 태깅하고 `X-Request-Id` 응답 헤더로 반환. 구현 축 3개: (1) 신규 `RequestLoggingFilter`(`:app:api` `common/logging/`, `Ordered.HIGHEST_PRECEDENCE`, URL 패턴 `/api/*`) — `MDC.put("requestId")`·진입 로그(메서드·경로+쿼리)·응답 로그(status·elapsedMs, SLF4J `addKeyValue`)·`finally { MDC.clear() }`(스레드풀 오염 방지), (2) `JwtAuthenticationFilter` 파싱 성공 시 `MDC.put("memberId")` 1줄(비인증 경로는 자연히 부재), (3) `GlobalExceptionHandler` 에러 로그 표준화(예외 타입·ErrorCode·status·uri, 4xx WARN/5xx ERROR+스택) + catch-all `Exception` 핸들러 신설(`ErrorCode.INTERNAL_SERVER_ERROR("COMMON-003", 500)` 채번 — 미처리 예외도 `BaseResponse` 봉투 유지). 로그 출력 이원화: local/dev 는 `logging.pattern.correlation: "[%X{requestId:-}][%X{memberId:-}] "`(베이스 yml), staging/prod 는 Boot 4.1 내장 structured logging `logging.structured.format.console: ecs`(**신규 의존성 0** — logstash-logback-encoder 불필요, MDC 가 JSON 필드로 자동 포함). **`app/api` 의 `logback-spring.xml` 은 삭제했다** — 커스텀 logback 설정이 있으면 Boot 이 패턴 인코더를 고정해 `logging.structured.format.*` 이 조용히 무시된다(`base.xml` → `console-appender.xml`). api 에 logback xml 을 다시 추가하지 말 것(`LogOutputConfigTest` 가 가드, `StructuredConsoleLoggingTest` 가 ECS 출력 검증). 쿼리 파라미터는 진입 로그에 포함하되 `MASKED_QUERY_PARAMS`(현재 `emptySet()`) 목록 값만 `***` 마스킹. 계층별(컨트롤러·서비스·리포지토리) 진입/종료 로그는 범위 밖, 토큰·본문 미기록. 도메인·application·infra 모듈 무변경(`:core` 는 ErrorCode 상수 1건), DB·Flyway 무변경, MDC 는 ThreadLocal — api 동기 경로 전제(@Async/가상스레드 도입 시 전파 데코레이터 후속). 테스트: 마스킹 순수 함수 단위 + MockMvc·Logback ListAppender 통합(진입/응답 쌍·동일 requestId·헤더·memberId 유무·에러 표준 로그·MDC 청소). `:app:batch` 범위 밖.)

이전 플랜: `specs/kb-134-architecture-simplification/plan.md` (KB-134 아키텍처 단순화 — 클린아키텍처 ports&adapters 폐기(KB-101 흡수), 순수 구조 리팩토링·API/스키마 무변경. **:infra:persistence 해체** — 엔티티·Spring Data 리포지토리를 각 도메인 모듈로 이동(internal), 어댑터·리포지토리 port 6종(MemberRepository·FoodRepository·FoodScoringSource·AvoidanceSubstanceRepository·ScanHistoryRepository·RefreshTokenStore 인터페이스) 폐기, 도메인별 **도메인 서비스(MemberService·FoodService 등) 하나가 유일 public 창구**(경계는 Kotlin internal + 컴파일러 강제). RefreshTokenStore 는 :domain:member 의 Redis 구체 클래스로 이름 승계(data-redis 의존 이동). **모듈 리네임**: core/→domain/(`com.kbap.domain.<d>`), :core→:core(`com.kbap.core`) — BaseEntity·EntityStatus 를 :core 로(jakarta/hibernate compileOnly + Boot BOM), Testcontainers 공통 설정은 :core testFixtures 로. **JPA 연관관계 전면 제거** — 유일 대상 FoodJpaEntity @OneToMany(cascade) → FoodService 의 명시적 자식 save/delete + id 일괄 조회로 전환, 참조는 **값 클래스 FoodId·MemberId(:core, @JvmInline) + IdConverter base + 타입별 @Converter(autoApply)**(자기 PK 는 BaseEntity Long 유지). FK 제약은 스키마에 전부 기존재(fk_fas_*·fk_scan_history_*) — 신규 마이그레이션 0건 예상. **테스트 전략(사용자 결정: mockk 미도입)** — 페이크 port 유스케이스 단위 테스트 폐기, 시나리오를 (1) 도메인 모듈 통합 테스트(구 *RepositoryAdapterTest → <도메인>ServiceTest, MySQL Testcontainers) (2) app:api MockMvc 통합 테스트로 1:1 승계(유실 방지 매핑표를 tasks 에서 작성), 순수 로직 단위 테스트·LLM/auth seam 페이크는 유지(ScannedNameInterpreter·SocialTokenVerifier 등 비-리포지토리 seam 은 폐기 대상 아님, :infra:llm 무변경). **빌드**: kbap.domain-conventions 에 kotlin-spring·kotlin-jpa·Boot BOM·data-jpa(implementation)·api(:core) 추가(research/review 도 동일 적용), 부트앱 runtimeOnly 조립 제거, 카탈로그 spring-boot-starter-data-mongodb 삭제. **MongoDB 잔재 제거**(yml 블록 8곳·docker-compose mongo·prod exclude·카탈로그 — 코드 사용처 0건). **ArchUnit 전면 재작성**(Test-First 진입점: 새 규칙 Red → 이동 Green): 도메인 간 의존 금지·@Entity 는 domain 에만·연관관계 애너테이션 금지·컨트롤러 /api/v·app:api 엔티티 미참조. **헌법 개정이 첫 산출물** — 원칙 III·IV 대체(MAJOR v3.0.0) + ADR-0011(ADR-0006·0008 supersede) + CLAUDE.md 갱신. 기존 마이그레이션 파일 이동·리네임 금지(시드 동기화 테스트 결합). 선행 관계: 리뷰 기능(KB-128·129·131)보다 먼저.)

<!-- SPECKIT END -->
