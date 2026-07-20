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
- **서비스 메서드 네이밍 (고정 — 2026-07-18 개정, KB-170).** 서비스(도메인·애플리케이션) public 메서드는 다음 규칙을 따른다:
  - **조회는 `get~` 으로 통일 — `find` 접두 폐기.** 단건 `get~` = 없으면 `BusinessException`(반환 non-null). **null 이 도메인상 정상값인 경우에만** `get~OrNull`(반환 `T?`) — 예: 게스트 회원 조회 `getMemberOrNull`. 호출부가 null 분기를 소유해야 할 때(에러 코드가 호출부마다 다를 때 포함)만 허용한다.
  - **목록 조회**: `get~s`(빈 컬렉션 허용, throw 없음 — `getRandomReadyFoods`·`getAvoidedCodes`). **페이지 조회**: `get~Page` 로 명명하고 **반환 타입도 `~Page` 로 일치**시킨다(`getFoodPage: FoodPage`). List 를 반환하는 내부 로더에 `Page` 접미를 달지 않는다. 파생 계산 결과라도 맨명사 금지 — 동사로 시작.
  - **CRUD**: 생성 `create~` · 수정 `update~` · 삭제 `delete~`(소프트).
  - **도메인 행위는 유비쿼터스 언어 동사 그대로** — `login`·`logout`·`withdraw`·`completeOnboarding`·`assessMenuBoard`·`search`(`searchFoodPage`)·`findOrSignUp`·`increaseScanCount` 처럼 업무 용어를 CRUD/get 접두로 뭉개지 않는다(가장 우선하는 규칙). 검증 행위는 `verify~`(`verifyImageAccess`).
  - **보조**: boolean `is~/has~/exists~`, 개수 `count~`, 순차 공급 `next~`. (리포지토리 `findBy~` 는 Spring Data 파생 쿼리 계약으로 규약 밖.)
  - **MemberService 서비스 계약**: 조회는 항상 active(`member_status=ACTIVE`) 회원만 노출한다 — `getMember`/`getMemberOrNull` 이름에 active 를 생략하는 근거.
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
`specs/kb-188-profile-image-required/plan.md` (KB-188 프로필 사진 필수화 — 프로필 사진 계약을 "선택(null 허용·빈 문자열=제거)"에서 "필수(온보딩 필수 전송·빈 문자열 불가)"로 변경. 기본 이미지 선택은 클라이언트 책임 — 미설정도 기본 경로 `/images/default/profile/profile-default-512.png` 명시 전송. 변경 4지점: (1) `OnboardingRequest`·`MemberProfileInput`·`Member.completeOnboarding` 의 profileImageUrl non-null 타입 강제(미전송/null → 역직렬화 실패 → 기존 핸들러 400 COMMON-002 — KB-158 spiciness 선례), (2) `MemberProfile.validatedImagePath` 빈 문자열 제거 센티널 폐기 → 400 MEMBER-008(전체 URL 거부·길이 512 유지, 반환 String non-null 화) — 온보딩·수정이 이 함수를 공유해 한 곳 수정으로 두 경로 커버, `updatedWith` 사진 3분법→2분법(null=유지·값=검증 교체), (3) 프로필 수정 null=유지 규약(KB-124) 불변, (4) Flyway 백필 1건 — `JSON_SET` + `WHERE JSON_EXTRACT(...) IS NULL OR JSON_TYPE(JSON_EXTRACT(...)) = 'NULL'`(키 부재·JSON null 동시 커버 — `JSON_UNQUOTE` 단일 조건은 JSON null 을 문자열 'null' 로 반환하는 MySQL 함정으로 기각, 소프트 삭제 행 포함, 멱등·순서 독립). `MemberProfile.profileImageUrl` 타입은 `String?` 유지 — 온보딩 전 회원은 구조적 null(계약 밖). `MemberProfileJson`·`ProfileUpdateRequest/Input`·응답 조립(`ImageUrls.resolve`)·DB DDL 무변경. Test-First: `MemberProfileTest` 빈 문자열 shouldThrow + `MemberControllerTest` 온보딩 미전송 400·빈 문자열 400·백필(마이그레이션 리소스를 읽어 직접 실행 — 파일명 결합 주의, 리소스 부재 시 명시 실패) + `ScenarioApiDriver.온보딩한다` 기본 파라미터 추가. 프로덕션 5파일+SQL 1건, :app:batch 범위 밖.)

이전 플랜: `specs/kb-172-deploy-automation/plan.md` (KB-172 브랜치별 배포 자동화 — 수동 배포(로컬 빌드→ECR push→EC2 접속 docker 명령)를 브랜치 푸시만으로 자동화. 환경별 워크플로 3파일: `deploy-dev.yml`(develop→공용 EC2 `kbap-api-dev` :8080)·`deploy-staging.yml`(staging→`kbap-api-staging` :8081)·`deploy-prod.yml`(main→ECS 네이티브 블루/그린, CodeDeploy 미사용 — 사용자 확인 07-20). 공통 흐름: 기존 멀티스테이지 Dockerfile 로 linux/amd64 빌드→ECR `kbap/api` push(**태그 전 환경 공통 `${{ github.sha }}`** — 커밋마다 유일 태그라 충돌·덮어쓰기 없음, `latest`·버전 개념 폐기(사용자 결정 07-20 — VERSION 파일·브랜치명 파싱·git tag 전부 기각, 릴리스 버저닝은 후속))→dev/staging 은 SSM Run Command(AWS-RunShellScript)로 pull/stop/run(--env-file /opt/kbap/*.env — 비밀은 호스트 소유)+**스크립트 내 헬스체크 루프**(실패=명령 실패=워크플로 실패, 완료대기는 get-command-invocation 종료상태 폴링 300초 — aws ssm wait 100초 오탐 회피, Codex 리뷰 반영), prod 는 describe-services→describe-task-definition→이미지만 교체 리비전 등록→`ecs update-service --task-definition`(블루/그린 트리거)→**PRIMARY 배포 `rolloutState` 폴링**(COMPLETED=성공·FAILED=실패, 타임아웃 `vars.DEPLOY_TIMEOUT_SECONDS` 기본 30분 — 고정 `wait services-stable` 10분은 bake>10분 정상배포 오탐이라 대체, 리뷰 지적). 블루/그린 전략·타깃그룹·bake time 은 ECS 서비스 사전 구성(인프라 소유), `--deployment-configuration` 미전달(bake·서킷브레이커 덮어쓰기 방지). 인증: GitHub OIDC + 환경별 IAM 역할 3개(gha-deploy-dev/staging/prod), 교차 배포 차단은 신뢰정책 `sub`=`repo:…:environment:<env>`(어느 환경) + GitHub Environment deployment branch policy(어느 브랜치, prod→main·staging→`staging-*`·dev→develop) 2겹(OIDC sub 은 브랜치 미포함 — Codex 리뷰 반영). 값은 GitHub Environments(dev/staging/prod) **variables**(secret 0개), prod 승인 게이트는 미설정(추후 protection rule 만). 동시성 `concurrency: deploy-<env>, cancel-in-progress: false`(마지막 푸시=최종 상태). 롤백: 세 워크플로 공통 `workflow_dispatch`+`image_tag`(이전 커밋 sha, env 로 받아 형식 검증=셸 인젝션 차단) 재배포. reusable workflow·taskdef/appspec 저장소 반입·gradle 별도 빌드·buildx 캐시 전부 기각(YAGNI). **프로덕션 코드·설정·DB 0줄** — JVM 테스트 표면 없음(KB-169 선례), 검증은 actionlint+환경별 실배포 1회 런북(quickstart §6). AWS 측 구성(OIDC provider·IAM·EC2 env-file·Environments+branch policy·임시 staging 브랜치)은 quickstart 런북. data-model·contracts 없음, :app:batch 배포 범위 밖.)

이전 플랜: `specs/kb-171-storage-key-prefix/plan.md` (KB-171 이미지 업로드 객체 키 환경 접두(key-prefix) 지원 — prod S3 버킷을 전 환경이 공유하므로 업로드 API 경유 이미지(용도 scan·review·profile)의 객체 키에 환경별 최상위 폴더 접두를 붙인다. `kbap.storage.key-prefix` → `ImageUploadConfig` `@Value` 주입 → `ImageUploadProperties.keyPrefix` → `ImageUploadApplicationService.objectKey()` 에서 `trim('/')` 정규화 후 `"$prefix/$key"` 결합(빈 값이면 무접두 — `dev`/`dev/`/`/dev` 동일 결과, 선행·중복 슬래시 구조적 불가). **yml 기본값(07-20 사용자 결정, KB-169 관례)**: 전 환경 환경명 기본값 — base `${STORAGE_KEY_PREFIX:local}`·테스트 yml `local`·프로필 `${STORAGE_KEY_PREFIX:dev|staging|prod}` — 인프라 env 없이 배포만으로 분리, env 로 커밋 없이 반전(빈 값=무접두 계약은 단위 테스트 고정). **음식 사진은 환경 공용 `images/menus/…`** — 업로드 API 미경유라 접두 비적용이 구조적으로 보장(전 환경 동일 경로 참조). 접두 포함 키가 그대로 DB ref 저장 → `ImageUrls.resolve` 조립 무변경, API 계약·DB 스키마·Flyway 0, IAM 권한 제한(인프라)·:app:batch 범위 밖. Test-First: `ImageUploadApplicationServiceTest` 접두 유무·슬래시 변형 Red→Green 완료 — 프로덕션 3파일+yml 4파일 수정, 신규 파일 0.)

이전 플랜: `specs/kb-169-redis-tls/plan.md` (KB-169 prod Redis TLS 필수 대응 — prod ElastiCache(전송 중 암호화 필수·클러스터 모드 활성·샤드 1)에 앱이 평문 TCP 접속을 시도해 refresh token 저장 실패 → 로그인 API 500. 해결: Boot 4.1 `spring.data.redis.ssl.enabled` 를 **4개 환경 프로필 yml 전부에 동일 선언**(사용자 지시 — Jira DoD 의 "staging 확인 후 판단" 대체). 기존 `${REDIS_HOST}`/`${REDIS_PORT:6379}` 관례대로 `ssl.enabled: ${REDIS_SSL_ENABLED:기본값}` — dev·staging·prod 기본 `true`(인프라 env 추가 없이 배포만으로 해소), local 기본 `false`(평문 docker Redis 비파괴), env 로 커밋 없이 반전 가능(dev 홈서버 평문 판명 대비). **프로덕션 코드 0줄** — Lettuce TLS 는 Boot 자동구성이 프로퍼티만으로 적용, `rediss://` URL·ClientOptions 빈·cluster.* 전환(단일 샤드는 standalone 접속 동작) 전부 기각. 기존 테스트 무영향(테스트 전용 application.yml 이 프로필 yml 미로드 — Testcontainers Redis 평문 유지). 리소스 가드 테스트(RedisSslConfigTest)는 Red→Green 후 제거(사용자 결정 — yml 복창이라 정보량 낮음, 설정만 변경이라 테스트 표면 없음), 검증은 배포 후 런북(quickstart §3). data-model·contracts 없음(엔티티·API 계약 변경 0), Flyway 0, :app:batch(Redis 미사용) 범위 밖.)

이전 플랜: `specs/kb-170-service-lookup-get-naming/plan.md` (KB-170 서비스 조회 메서드 네이밍 get 통일 — 도메인·애플리케이션 서비스의 조회 메서드를 `get~`(없으면 `BusinessException`/non-null)으로 통일하고 null 이 도메인상 정상값인 단건만 `get~OrNull`(반환 `T?`)로 남긴다. `find` 접두 폐기, 단 유비쿼터스 동사(`search`·`findOrSignUp`)·보조(`next~`·`count~`·`is/has/exists`)·행위(CRUD·도메인 동사)는 규약 밖. 컬렉션=`get~s`(빈 값 허용), 페이지=`get~Page`(이름·반환타입 `~Page` 일치), List 반환 내부 로더는 `Page` 접미 제거해 컬렉션 규칙(`get~s`, internal)으로 흡수. 리네임 12건: MemberService(findActive→getMemberOrNull, private findActiveOrThrow→public getMember — active 는 "MemberService 조회는 항상 active 회원만 노출" 서비스 계약으로 규약화해 이름에서 생략), FoodService(browse→getFoodPage, search→searchFoodPage, findFoodPage→getFoods(internal), searchFoodPage(로더)→getFoodsByKeyword(internal), findReadyById→getReadyFood[null→내부 throw FOOD_NOT_FOUND], findRandomReady→getRandomReadyFoods, findAllReadyByIds→getReadyFoodsByIds, findByKoreanMatchKeys→getFoodsByKoreanMatchKeys), BookmarkService(findBookmarks→getBookmarkPage, findBookmarkedFoodIds→getBookmarkedFoodIds), ScanService(findRecentReadyFoodIds→getRecentReadyFoodIds), AvoidanceCatalogService(findByCodes→getSubstancesByCodes). `ImageUploadService.findVerifiedImage` 는 조회가 아닌 검증 행위로 재분류→`verifyImageAccess`(존재+소유 검증, 반환타입·TODO 주석·미사용 유지, ScanService 배선은 범위 밖). 외부 API 계약(요청·응답·에러코드·HTTP 상태) 무변경 순수 리팩터링 — DB 스키마·Flyway·엔티티·모듈 그래프 0, :app:batch·`nextChunk` 범위 밖. Test-First: 계약 이동 유일 지점 getReadyFood 만 테스트를 `shouldThrow` 로 먼저 Red 후 구현, 나머지는 테스트 동반 리네임으로 Green 유지. CLAUDE.md 서비스 메서드 네이밍 규약 문구 갱신 포함.)

이전 플랜: `specs/kb-154-image-path-cdn/plan.md` (KB-154 이미지 참조 경로 저장 + 응답 조립 시 CDN 조합 — 프로필 사진·음식 이미지 참조를 DB 에 CDN 도메인 없는 경로(키)만 저장하고, 응답 조립 시 서비스 레이어(MemberService.getMyProfile·FoodService.getDetail/foodPage·HomeApplicationService)가 `kbap.storage.public-base-url`(KB-145 재사용, 환경별 `IMAGE_PUBLIC_BASE_URL`)을 `@Value` 주입받아 `:core` Spring-free 헬퍼 `ImageUrls.resolve(base, ref)` 로 완전한 URL 조합. resolve 규칙: null→null, `http(s)://` 시작→그대로(레거시 통과), base 빈 값→ref 그대로, 그 외 슬래시 정규화 접합. 프로필 입력 검증은 허용 호스트(https URL) → 경로 검증(`http(s)://` 시작 거부, MEMBER-008 재사용)으로 대체 — `allowedImageHosts` 파라미터 체인·`kbap.member.profile-image-allowed-hosts` yml·`ProfileImageHostRestrictionTest` 폐기. DB JSON 키 `profileImageUrl`·컬럼 `image_ref`·API 필드명 불변(값 의미만 변경 — 입력=경로, 출력=완전 URL). Test-First: `ImageUrlsTest`(:core 신규) + `MemberProfileTest` 경로 검증 교체 + `MemberControllerTest` 저장=경로/응답=`https://cdn.test` 조합/전체 URL 400 + food 통합. Flyway 0·마이그레이션 0(레거시 절대 URL 행은 조립 시 통과)·신규 API 0·:app:batch 범위 밖.)

이전 플랜: `specs/kb-155-llm-cost-ledger/plan.md` (KB-155 메뉴 스캔 LLM 호출 비용 기록 원장 — vision 호출 비용(현재 로그만)을 append-only 테이블 `llm_call_cost` 에 1호출 1행 기록(모델명·input/output 토큰·USD·KRW, 환율 1500 `LlmPricing` 재사용). **기록은 비즈니스 로직과 분리**: `OpenAiMenuBoardVisionExtractor` 가 `chatModel.call()` 응답 수신 직후(파싱 전 — 응답 수신=과금) `:core` 의 Spring-free 이벤트 `LlmCallCostIncurred` 를 발행, `:app:api` 의 `@Async @EventListener`(`LlmCallCostEventListener`, 신규 `AsyncConfig` @EnableAsync)가 **신규 리프 모듈 `:domain:metering`**(사용량·비용 계량 컨텍스트 — 07-18 :domain:scan 에서 분리, 배치 LLM 확장 대비)의 `LlmCallCostService.record()`(@Transactional, record 만 노출=append-only)로 저장. 기록 경로(발행·비동기·저장) 어떤 실패도 스캔 응답 비전파 — 발행측 try/catch + 리스너 예외 삼킴(로그만). `@TransactionalEventListener` 금지(발행 지점이 무트랜잭션이라 유실). 금액 반올림은 이벤트 생성 시 1회(HALF_UP, USD 6·KRW 2자리 = 기존 로그 표기) → DECIMAL(12,6)/(14,2), 모델명은 `response.metadata.model` 우선·구성값 폴백. Flyway 1건(BaseEntity 공통 + created_at 인덱스, FK 없음). 조회/집계 API 범위 밖. api 통합 스캔 경로는 페이크 extractor 라 end-to-end 불가 — 발행측 단위(:infra:llm) + 소비측 통합(:app:api, kotest eventually) 분해 커버.)

이전 플랜: `specs/kb-167-e2e-scenario-tests/plan.md` (KB-167 E2E 시나리오 테스트 도입 — 핵심 사용자 여정 4종(해피패스·인증 생명주기·메뉴판 스캔·탈퇴)을 `app/api/src/test/.../scenario/` 에 Kotest BehaviorSpec 인수 테스트로 추가. **프로덕션 코드 0줄** — MockMvc 인프로세스 + 기존 Testcontainers(MySQL·Redis) + 기존 페이크(vision·S3) 재사용. **시나리오 본문은 `ScenarioApiDriver` 의 한국어 스텝 메서드 조립**(`회원가입한다()`→`온보딩한다()`→`스캔한다()`)으로 여정 서사화, 여정 상태(토큰·objectKey·foodId)는 드라이버 필드로 전달. 격리: 시나리오 전용 `@Primary` 소셜 페이크가 **idToken→sub 파생**(여정마다 UUID 토큰=신규 계정, 테이블 청소 없음 — 기존 고정 sub 페이크는 여정 간 회원 겹침). 만료 토큰은 `AuthTokenProperties.copy(accessTtl=음수)` 선례 재사용. "스캔 히스토리"는 전용 GET 이 없어 홈 `recentScans` 로 검증. 음식 시드는 `ScenarioFoodSeed` insert-if-absent(auto-increment id, **카탈로그 81종 DELETE 금지** — 기존 FoodTestSeed.clear 는 카탈로그 파괴라 시나리오에서 금지). `@Tags("scenario")` 로 선별/제외(-Dkotest.tags). 도메인 모듈·:app:batch·프로덕션 yml 범위 밖.)

이전 플랜: `specs/kb-163-flyway-squash/plan.md` (KB-163 Flyway 마이그레이션 스쿼시 — 기존 22개(스키마+데모 시드 혼재)를 최종 상태 기준으로 **`db/migration` 2파일로 압축**: 스키마 전용 `init_schema`(테이블 7종, INSERT 0) + 마스터 시드 `seed_avoidance_catalog`(기피물질 카탈로그 81종, translations JSON) — 전 환경 동일 적용. **데모 음식 시드(10건)는 더미라 아예 폐기**(2026-07-17 결정 — 초기 계획의 db/seed·locations 프로필 분기도 함께 폐기, yml 무변경). 최종 스키마는 docker MySQL 에 구 22개 적용 후 mysqldump 로 도출(신구 diff=0 검증 — member 컬럼 CHARACTER SET 표기 차이는 덤프 정규화일 뿐 동일). **핵심 판단: 홈서버 dev DB 는 Jira 원안(drop 재생성) 대신 flyway_schema_history 재기준선(baseline-version=2026.07.16.21.38.42)으로 회원·음식 데이터 손실 0 전환** — 리허설로 행 수 100% 보존 검증(quickstart §4 런북). Test-First: 신규 `MigrationLayoutTest`(리소스 가드 — db/migration 정확히 2파일·init 무INSERT·마스터 81건·INSERT INTO food 부재)가 Red 진입점 + `AvoidanceCatalogSeedSyncTest` 경로·3필드 파싱 갱신(파일명 결합 주의사항) + `MigrationValidationTest` 는 카탈로그 검증으로 교체. 신규 코드 0줄 — SQL 2개·테스트 3개·삭제 22개. 도메인 모듈·:app:batch(flyway off) 범위 밖.)

이전 플랜: `specs/kb-158-spiciness-skip/plan.md` (KB-158 맵기 선호 미설정(스킵) 허용 — 미설정을 **-1 센티널**로 저장(0~10 범위 밖). 클라이언트 계약(사용자 확인 07-16·17): 스킵/"설정 안 함"은 **-1 명시 전송**, **온보딩은 맵기 필수 필드**(-1~10 반드시 전송, 미전송=400 COMMON-002 — nickname 처럼 non-null 타입 강제). 프로필 수정 API 는 공용이라 미전송(null)=유지 규약(KB-124) 불변 — -1 명시=미설정 복귀. 변경 4지점: (1) `:domain:member` `MemberProfile` — `DEFAULT_SPICINESS_PREFERENCE(5)` 폐기→`SPICINESS_UNSET(-1)`, 허용 집합 `{-1} ∪ 0..10`(`init` require·`validatedSpiciness` 동일 조건), `empty()`·`MemberProfileJson` 기본값이 상수를 따라감. (2) 온보딩 경로 필수화 — `OnboardingRequest`·`MemberProfileInput`·`Member.completeOnboarding` 의 spiciness 를 non-null `Int` 로(배포 전 가입 회원 저장값 5 잔존 회귀도 구조적으로 소멸). (3) `:core` `ErrorCode` MEMBER-009 메시지에 -1(미설정) 허용 반영. (4) `:app:api` `MemberApi` Swagger 문구. DB 스키마·Flyway·엔티티 구조·모듈 그래프 무변경. 역직렬화 기본값 5→-1 은 방어적(실 DB 는 consolidation 이 전 행에 키 백필 — 키 부재 행 없음, 기존 표시값 변화 0건). Test-First: `MemberProfileTest`(-1 허용·-2 거절·updatedWith 3분법) + `MemberControllerTest`(온보딩 생략/-1→조회 -1, 수정 -1→복귀, 생략→유지, 11→400 MEMBER-009). 마이그레이션 0, :app:batch 범위 밖.)

이전 플랜: `specs/kb-145-presigned-url/plan.md` (KB-145 이미지 업로드용 S3 presigned URL 발급 API — 인증 사용자에게 **업로드용 presigned PUT URL** + 저장·표시용 **안정 공개(CDN) URL** 을 함께 발급하는 단일 엔드포인트 `POST /api/v1/images/upload-url`. 범용 이미지 업로드 창구(용도 purpose 로 구분, 첫 소비자=메뉴판 스캔 KB-138). **핵심 성질: SigV4 presign 은 로컬 서명이라 발급 시 S3 미호출 — DB·Flyway·엔티티·네트워크 왕복 전무.** 무소속 유스케이스 `ImageUploadApplicationService` + seam `PresignedUploadPort`(:application), S3 어댑터 `S3PresignedUploadPort` 는 **KB-138 이 만든 `:infra:storage` 모듈에 합류**(같은 모듈에 head/delete 용 `S3StorageObjectStore` 와 공존), 컨트롤러·DTO·빈 조립은 :app:api. 구현 축: (1) 서명=**Presigned PUT**(AWS SDK v2 **`s3` 만 의존** — presigner·client 모두 s3 아티팩트에 포함, BOM 2.48.0) — v2 는 POST policy 미제공이라 PUT 에 **정확 Content-Length(클라 신고값)+Content-Type** 을 서명해 크기 상한 강제(UPLOAD-003 발급 거절). (2) 읽기=**만료 없는 공개/CDN URL** `{public-base-url}/{objectKey}` — 별도 조회 서명·재발급 없음. (3) 객체 키 `{purpose-prefix}/{yyyy}/{MM}/{dd}/{memberId}/{UUID}.{ext}` — 충돌·추측 불가. (4) 부팅 안전=KB-138 과 통일된 패턴 — `app/api/config/StorageConfig` `@ConditionalOnProperty(kbap.storage.enabled=true)` 로 실 S3 빈(StorageObjectStore·PresignedUploadPort) 조립, 미구성(local/test)은 빈 없음 + 테스트 전역 페이크(`FakePresignedUploadPortConfig`·`FakeStorageConfig`). 정책값 `ImageUploadProperties`(허용 형식·max-bytes·ttl·public-base-url)는 무조건 빈(`ImageUploadConfig`). (5) :core ErrorCode 에 UPLOAD-001(형식)·002(용도)·003(초과). Test-First: `ImageUploadApplicationServiceTest`(페이크 port)=핵심 + `ImageUploadControllerTest`(MockMvc 401·400·200) + `S3PresignedUploadPortTest`(더미 정적 자격증명 로컬 서명). 마이그레이션 0, :app:batch·도메인 모듈 범위 밖.)


<!-- SPECKIT END -->
