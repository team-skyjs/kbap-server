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

`meogo/meogo-server` — Kotlin으로 작성된 Spring Boot 백엔드. Gradle 멀티모듈 **모듈러 모놀리스**다(ADR-0008). 모듈 접두어(`meogo-`)는 쓰지 않고 그룹 컨테이너(`core/`·`infra/`·`app/`)로 분류한다. **실행 가능한 bootJar 는 두 개** — `:app:api`(web, 진입점 `com.meogo.MeogoApiApplication`)와 `:app:batch`(배치, 진입점 `com.meogo.app.batch.MeogoBatchApplication`)다. 공유 계층(`core`·`application`·`infra`)을 최상위에 두고 **두 부트앱이 필요한 모듈을 직접 의존해 도메인/영속/외부 어댑터를 재사용**한다(ADR-0008 — 기존 "batch 는 meogo-api 무의존·이벤트 전용" 디커플 정책을 대체). DB 는 원래 공유였고(batch `flyway off`, 스키마 owner=api), 이제 도메인/엔티티도 단일 소스로 공유한다. `:common`은 두 앱이 공유하는 통합 이벤트·기술 공통(도메인/web/jpa 무의존). 공통 빌드 설정은 `buildSrc` 컨벤션 플러그인에 둔다. 아직 비즈니스 코드는 거의 비어 있는 스캐폴드 상태다.

### 모듈 구조

상세 결정 근거는 [ADR-0008](docs/adr/0008-modular-monolith-shared-domain.md)·[`docs/architecture/meogo-api-module-structure.md`](docs/architecture/meogo-api-module-structure.md) 참고. 의존 방향은 단방향으로 고정한다.

```
:common  ← (모두 의존 가능 / 아무에게도 의존 안 함)

core:kernel ← core:도메인(food/member/scan/avoidance/research/review) ← application
   ▲    ▲                              ▲
   │  infra:persistence (도메인 port 구현, JPA)        │ implementation
   │       ▲ runtimeOnly                               │
   └───────┴──────────── app:api (web bootJar, 조립·Flyway owner)
                         app:batch (배치 bootJar, core 도메인 직접 의존 + infra 조립, flyway off)
```
(클린아키텍처 ports&adapters: 도메인은 ORM-free·**완전 Spring-free** 로 model+port+도메인서비스/정책을 갖고(`@AggregateRoot` 는 순수 마커라 spring-context 불요), infra(외부)·persistence(JPA)가 각각 port 를 구현한다. port 구현체는 부트앱(`app:api`·`app:batch`)이 `runtimeOnly`로 런타임 주입한다. **batch 도 동일 도메인/영속을 공유**한다 — 중복 없는 단일 소스.)

- `:app:api`: web bootJar — controller, API DTO. **조립 책임** — `:infra:persistence`를 `runtimeOnly`로 결합해 adapter 빈을 런타임 DI 로 연결(컴파일 의존 X). 진입점 `MeogoApiApplication`은 `com.meogo` 루트(스캔·AutoConfigurationPackages 가 전 계층 커버). DB 마이그레이션(Flyway) **스키마 owner** — `src/main/resources/db/migration`. 패키지 `com.meogo.app.api`.
- `:application:*`: 유스케이스 조율, transaction boundary. 외부 client 는 **`:core`의 port 인터페이스로만** 사용하고 infra 구현체에 직접 의존하지 않는다(계층 역전 방지). **컨텍스트 간 조합은 여기서만**. `application/`은 **진입점별로 분할**한다 — 현재 `:application:client`(사용자 API 유스케이스)만 존재. 여러 진입점(api·batch·admin)이 공유하는 교차 도메인 유스케이스(예: "납부")는 추후 `:application:shared`에 한 번만 구현하고 각 `:application:<진입점>`이 의존한다(KakaoPay식). 패키지 `com.meogo.application.<진입점>`(예: `com.meogo.application.client`).
- 도메인 컨텍스트 모듈: `core/` 컨테이너 직속 — active: `:core:{food,member,avoidance,research}`, deferred placeholder: `:domain:review`. 각 도메인은 `:core`만 바라보는 **ORM-free·완전 Spring-free 모듈**(model + port + 도메인 서비스/정책). stereotype `@AggregateRoot`는 순수 마커라 spring-context 가 필요 없다(빈 등록 서비스/정책은 `:application:*`에). 영속(JPA)은 `:infra:persistence`가 구현. 패키지 `com.meogo.core.<도메인>`. (`research`는 배치 전용 — web 미노출.)
- `:core`: 공통 타입·예외·이벤트 계약·유틸·도메인 stereotype(`@AggregateRoot` **순수 마커** — @Component 메타 아님)·`RiskLevel`, 외부 client **port 인터페이스**, 그리고 **여러 도메인이 공유하는 vocabulary**(예: `LanguageCode` — food·avoidance 공용). 특정 컨텍스트가 소유하는 코드(예: 회피·주의 성분 `AvoidanceCategory`/`AvoidanceSubstance`)는 kernel 이 아니라 **소유 컨텍스트 모듈**(avoidance)에 두고, 타 컨텍스트는 코드로만 참조한다(원칙 II). **완전 Spring-free**(`kotlin-common`만, spring-context 불요 — `@DomainService` 폐기·`@AggregateRoot` 마커 도입 결과, ADR-0006). 패키지 `com.meogo.core`.
- `:infra:persistence`: JPA/ORM 영속 어댑터. **각 도메인 모듈을 `implementation`으로 의존**해 리포지토리 port 를 구현하고 **영속 엔티티 관리**(엔티티·Spring Data Repository·`RepositoryAdapter`·`BaseEntity`·`EntityStatus`, 패키지 `com.meogo.infra.persistence.*`). 부트앱이 `runtimeOnly`로 조립. (LLM 호출 어댑터는 전용 모듈 `:infra:llm` 신설 — ADR-0010, `:app:batch`가 `implementation`으로 직접 의존.)
- `:app:batch`: 배치 bootJar. **필요한 `:core:도메인`(+추후 `:application:batch`/`:application:shared`)·`:infra:persistence`를 직접 의존**해 같은 도메인/DB 를 재사용(ADR-0008 — 더 이상 이벤트 전용 디커플 아님). **flyway off**(스키마 owner=api — 중복 적용 방지). 현재 application 미의존(자기 잡에서 도메인 port 직접 사용). 패키지 `com.meogo.app.batch`.
- `:common`: 통합 이벤트·기술 공통(logback 조각·유틸·횡단 어노테이션). 두 앱 공유. **web/jpa/도메인 의존 금지**(가볍게 유지). **Spring-free.** 통합 이벤트는 도메인 타입을 참조하지 않고 평면 값(ID·코드·스냅샷)만 담는다. 패키지 `com.meogo.common`.

각 모듈은 `src/main`·`src/test` 소스셋을 모두 가진다. **모듈 간 project 의존은 `api`가 아니라 `implementation`을 기본으로** 한다 — `:application:client`는 도메인/코어를 `implementation`으로 의존하므로 도메인 타입이 `:app:api`의 **컴파일 클래스패스로 전이되지 않는다**(런타임에만 전이되어 빈·컴포넌트 스캔·JPA 정상). 따라서 web 은 application 의 공개 타입(Input/Result 등)만 보고 JPA Entity·도메인 엔티티를 직접 import 할 수 없다. 이 경계(계층 의존 방향·도메인 Spring/ORM-free·도메인 컨텍스트 격리·application→infra/app 금지·app:api→persistence/도메인 금지·`@Entity` 위치)는 ArchUnit 테스트 `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt` 로 강제한다(`:app:api` 가 전 모듈을 런타임에 보므로 `com.meogo` 전체를 스캔). app:batch 미러링·컨트롤러 경로(`/api/v`) 검증은 후속.

## 설계 / 문서 위치

- **백엔드 아키텍처**(DDD·바운디드 컨텍스트·모듈 구성·데이터/AI 파이프라인) → [`docs/architecture/`](docs/architecture/). 강제 규칙은 `docs/architecture/meogo-conventions.md`.
- **의사결정 기록(ADR)** → [`docs/adr/`](docs/adr/). SpecKit 사이클마다 중요한 결정을 남긴다.
- **구현 설계**(기능별 "어떻게") → SpecKit `specs/NNN-slug/`(spec·plan·tasks). 교차-컨텍스트 흐름은 `mermaid-flows` 스킬로 시퀀스 다이어그램을 그린다.
- **제품 개요·기획 PRD("무엇을/왜")** → 공유 허브 `agent-hub/`(이 repo에선 git-ignored, 별도 서브모듈로 관리). 구현 세부는 여기 두지 않는다.

## 기술 스택

- **Kotlin 2.3** / **JVM (Java 21 toolchain)** — Gradle toolchain이 JDK를 해석하므로 로컬 `JAVA_HOME`에 묶이지 않는다(`settings.gradle.kts`의 foojay-resolver가 자동 프로비저닝).
- **Spring Boot 4.1** — web/validation/actuator/data-jpa/data-mongodb 스타터. 영속: **MySQL**(prod, `mysql-connector-j`) + H2(test) + MongoDB. DB 마이그레이션: **Flyway**(+flyway-mysql). API 문서: **springdoc-openapi**(Swagger UI). JWT: 구현 시 결정(아직 미추가).
- **LLM: Spring AI 2.0**(Boot 4 호환 라인) — 전용 모듈 **`:infra:llm`**(ADR-0010)에 `spring-ai-starter-model-openai` + `spring-ai-starter-model-google-genai`. 공개 API `LlmFanoutClient`·값타입·구성이 이 모듈에 응집되고 **`:app:batch`가 `implementation`으로 직접 의존**해 잡에서 호출(단일 소비자=배치, `:core` port·`runtimeOnly` 조립은 생략 — web/application 재사용 시 kernel port 승격 후속). 3개 모델(OpenAI·Upstage·Gemini)을 `meogo.llm.*` 프로퍼티 + `@ConditionalOnProperty`로 명시 구성: Upstage는 OpenAI 호환이라 openai 스타터를 base-url만 교체해 재사용, Gemini는 google-genai 스타터(API 키 방식). 키/활성 플래그가 없으면 caller 빈이 미생성돼 batch/web 부팅이 안전하며, Spring AI 자동구성 유입은 `application.yml`의 `spring.ai.model.*=none`으로 차단. fan-out은 JDK21 가상스레드 + `CompletableFuture`, 단일모델 seam `LlmModelCaller`로 부분실패를 페이크 단위검증(헌법 I).
- 빌드 도구: **Gradle (Kotlin DSL)**, 래퍼 사용.
- 테스트: **JUnit 5 플랫폼**(`useJUnitPlatform`) + **Kotest**(`kotest-runner-junit5` + `kotest-assertions-core`). Spring 모듈은 `spring-boot-starter-test`도 추가.

### 빌드 구성 (버전·공통 설정 관리)

> 입문 설명서: [`docs/guides/gradle-made-easy.md`](docs/guides/gradle-made-easy.md).

- **버전 카탈로그** `gradle/libs.versions.toml`이 모든 버전(라이브러리·플러그인)의 단일 출처다. `libs.*` 접근자로 참조한다. 스타터 버전은 대부분 Spring Boot BOM이 관리하므로 카탈로그에 버전을 적지 않는다.
- **공통 설정은 `buildSrc` 의 컨벤션 플러그인**(미리 컴파일된 `meogo.*.gradle.kts`)에 둔다. 각 모듈은 `plugins { id("meogo.<archetype>") }` 한 줄로 자기 아키타입을 선언한다. 루트 `build.gradle.kts`는 거의 비어 있다(집계 전용).
  - `meogo.kotlin-common` — **모든 leaf 모듈** 공통: kotlin-jvm·java-library, Java 21 toolchain, Kotlin 엄격성 플래그, `group`/`version`, 공통 테스트(Kotest + JUnit launcher + `useJUnitPlatform()`). Spring-free 모듈(core/common)은 이것만 적용.
  - `meogo.spring-conventions` — **Spring 라이브러리 공통**(core/common 제외): kotlin-common 위에 kotlin-spring·dependency-management·Spring Boot/AI BOM·`kotlin-reflect`/`jackson-module-kotlin`/`spring-boot-starter-test`를 얹는다.
  - `meogo.spring-boot-application` — **부트 앱(bootJar)**: `:app:api`, `:app:batch`. spring-conventions 위에 `org.springframework.boot`.
  - `meogo.domain-conventions` — **도메인 컨텍스트 공통**(food/member/scan/avoidance/research + review placeholder): kotlin-common 위에 `api(:core)` 만 얹는다. **완전 Spring-free** — JPA/Mongo·web·tx·kotlin-spring·spring-context·dependency-management 모두 끌어오지 않는다(`@AggregateRoot` 마커는 순수 애너테이션이라 spring 불요). (도메인 build 파일이 한 줄로 줄어든다.)
- **모듈별 고유 설정만 각 모듈 `build.gradle.kts`** 에 둔다(app:api=web/validation/actuator/flyway/springdoc+application:client·infra 조립, infra:llm=spring-ai, app:batch=필요 도메인·infra 조립 등). 모듈 build 파일에서 의존성은 **문자열 표기**(`"implementation"(...)`)로 적는다(플러그인이 컨벤션에서 적용돼 타입 안전 단축표기 미생성). 라이브러리 좌표는 모듈 build 파일에선 `libs.*`로 정상 사용.
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
./gradlew :app:api:test --tests "com.meogo.app.api.MeogoApiApplicationTests"          # 단일 테스트 클래스
./gradlew clean                          # 빌드 산출물 정리
```

실행 프로필은 `local`/`dev`/`staging`/`prod` 4종이다(`SPRING_PROFILES_ACTIVE`로 선택). 프로필 미지정 시 datasource 설정이 없어 테스트(`@SpringBootTest`)는 임베디드 H2로 동작한다.

별도 lint 태스크는 설정되어 있지 않으며, Kotlin null-safety 엄격성은 컴파일 단계에서 강제된다(아래 참고).

## 폴더별 지침 (CLAUDE.md) 운영

- 특정 폴더에 **그 폴더 작업 시 꼭 지켜야 할 규칙/관례**(코드만 봐선 알 수 없고, 일관되게 강제돼야 하는 것)가 있다고 판단되면, **그 폴더에 `CLAUDE.md`를 만들지 사용자에게 먼저 물어본다.** 임의로 만들지 않는다.
- 이유: 하위 폴더의 `CLAUDE.md`는 그 하위 트리의 파일을 다룰 때 자동 로드되므로, 규칙을 "가장 가까운 곳"에 두면 매번 확실히 적용되고 노이즈도 없다.
- 규칙은 가장 좁은 적용 범위의 폴더에 둔다(예: PRD 전용 규칙 → `agent-hub/prd/CLAUDE.md`). 상세 템플릿/레퍼런스는 같은 폴더의 다른 문서로 분리하고 `CLAUDE.md`엔 핵심 규칙+포인터만 둔다.

## 디렉터리 생성 규칙

- **디렉터리가 (다른 파일 없이) 빈 채로 남는 경우에만 `.gitkeep`을 추가한다.** (빈 디렉터리는 git이 추적하지 않으므로) 이미 파일이 있는 디렉터리에는 넣지 않는다.

## 컨벤션

- **Kotlin 소스 코드(`.kt`)에 주석을 작성하지 않는다 (고정).** 라인(`//`)·블록(`/* */`)·KDoc(`/** */`) 모두 금지하며, main·test 동일하게 적용한다. 코드는 이름(클래스·함수·변수)과 구조로 의도를 드러내는 **self-documenting** 방식으로 쓴다. 설명이 필요한 맥락(설계 근거·트레이드오프·"왜")은 코드가 아니라 **커밋 메시지·`docs/`·ADR·SpecKit 문서**에 남긴다. (예외: 빌드 스크립트 `*.gradle.kts`, Flyway SQL, `*.yml` 등 비-Kotlin 파일의 주석은 이 규약 밖이며 허용한다.)
- 소스는 각 모듈의 `src/main/kotlin/...`, 테스트는 `src/test/kotlin/...`에서 동일 구조로 미러링한다. **패키지는 모듈 경로를 미러링해 `com.meogo.<layer>` 로 둔다** — 커널 `com.meogo.core`(`core/kernel`), 도메인 컨텍스트 `com.meogo.core.<context>`(예: `core/food/src/main/kotlin/com/meogo/core/food/`), 유스케이스 `com.meogo.application.<진입점>`(예: `com.meogo.application.client`), 영속 `com.meogo.infra.persistence`, web `com.meogo.app.api`(컨트롤러·DTO·`BaseResponse`), 배치 `com.meogo.app.batch`, 공유 `com.meogo.common`. **부트 진입점 `MeogoApiApplication`은 패키지 루트 `com.meogo`** 에 두어 기본 컴포넌트 스캔·AutoConfigurationPackages 가 전 계층(엔티티·리포지토리 포함)을 커버한다(별도 `scanBasePackages` 불필요). 배치 진입점은 `com.meogo.app.batch`.
- web 실행 설정은 `app/api/src/main/resources/`에 YAML로 둔다: 베이스 `application.yml` + 프로필별 `application-{local,dev,staging,prod}.yml`. 확장자는 `.yml`로 통일한다(`.yaml` 아님). 테스트용 오버라이드는 `app/api/src/test/resources/application.yml`(Flyway off, H2 `create-drop`). 배치는 `app/batch/src/main/resources/application.yml`(flyway off). 공통 로깅은 `common`의 `logback-common.xml`을 각 앱 `logback-spring.xml`이 `<include>`로 가져간다.
- 컴파일러 엄격성 플래그는 `buildSrc`의 `meogo.kotlin-common` 컨벤션 플러그인에서 전 모듈에 일괄 적용되며, 신규 코드도 이를 준수해야 한다:
  - `-Xjsr305=strict` — JSR-305 nullability 애너테이션을 강제 제약으로 취급(Spring/Java API 호출 시 영향).
  - `-Xannotation-default-target=param-property` — Kotlin 프로퍼티의 기본 애너테이션 타깃을 변경.
- **테스트 스타일 (고정).** **모든 테스트는 Kotest `BehaviorSpec` 으로 통일**한다(다른 Spec 스타일·JUnit `@Test` 금지). 구조는 **`given("대상/전제") > \`when\`("상황") > then("기대 결과")`** 를 기본으로 하며, `given`·`` `when` ``·`then` 설명은 **한국어**로 쓴다(예: `` given("BoundingBox 생성") { `when`("x 가 음수이면") { then("예외를 던진다") { ... } } } ``).
  - **Spring 통합 테스트**(`@SpringBootTest`·MockMvc·repository)도 `BehaviorSpec` 으로 작성한다. `kotest-extensions-spring`(`io.kotest.extensions.spring.SpringExtension`)을 써서 클래스 본문 스타일(`class Foo : BehaviorSpec() { override fun extensions() = listOf(SpringExtension); @Autowired lateinit var ...; init { given... } }`)로 빈을 주입한다. `SpringExtension` 의존성은 `meogo.spring-conventions` 컨벤션 플러그인이 전 Spring 모듈 테스트에 일괄 제공한다.
  - MockMvc 는 `@AutoConfigureMockMvc` + `@Autowired MockMvc` 로 주입한다(`ObjectMapper` 빈은 주입 안 되므로 `jacksonObjectMapper()` 로 직접 생성).
- **JPA 연관관계 로딩 (고정).** 모든 연관관계(`@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany`)는 **`FetchType.LAZY`** 로 작성한다(`@ManyToOne`·`@OneToOne` 의 기본값 EAGER 도 명시적으로 LAZY 로 덮는다). 애그리거트 전체나 특정 연관을 함께 로드해야 하면 **fetch join 쿼리**(`@Query("… left join fetch …")`)로 명시적으로 가져온다 — EAGER 매핑으로 해결하지 않는다(N+1·불필요 로딩·`LazyInitializationException` 방지). 영속 어댑터가 트랜잭션 밖에서 도메인 매핑 시 컬렉션을 접근하면 fetch join 으로 미리 초기화한다.
- **JPA 엔티티 작성 (고정).** 모든 JPA 영속 코드(엔티티·Spring Data Repository·adapter)는 **`:infra:persistence` 모듈에 모은다** — 도메인 모듈은 ORM-free 로 두고(JPA·Spring Data 미포함), persistence 가 각 도메인을 `implementation` 으로 의존해 port 를 구현한다. 엔티티는 컨텍스트별 `com.meogo.infra.persistence.<도메인>` 패키지에 둔다(예: `com.meogo.infra.persistence.scan`). **모든 엔티티는 `com.meogo.infra.persistence.BaseEntity`(`@MappedSuperclass`)를 상속**한다 — `id`(IDENTITY)·`status`(`EntityStatus` ACTIVE/DELETED 소프트삭제, `active()/isActive()/delete()/isDeleted()`)·`createdAt`(`@CreationTimestamp`)·`updatedAt`(`@UpdateTimestamp`)를 공통 제공하므로 엔티티엔 **자체 id·생성/수정 시각을 두지 않는다**(도메인 고유 상태가 따로 있으면 `status` 와 컬럼명이 겹치지 않게 분리 — 예: scan 의 `scan_status`). BaseEntity·EntityStatus 가 모두 persistence 모듈 안에 있어 단일 모듈로 공유된다(모든 엔티티가 동일 모듈에서 상속). `kotlin-jpa`(no-arg) 플러그인이 persistence 에 적용되므로 **프로퍼티 기본값으로 no-arg 를 흉내내지 않아도 된다**. JPA 애너테이션은 **use-site 타깃 없이**(`@Id`/`@Column`, `@field:` 불필요 — field-only 타깃이라 자동으로 field 에 적용) 단다.
  - **컬럼 정의는 MySQL 기준으로 고정한다 (H2 호환은 고려하지 않는다).** 문자열 컬럼은 `@Column(length = N)` 으로 길이를 명시하고(예: `length = 20`), 길이 없는 `columnDefinition = "VARCHAR"` 같은 비-MySQL 형식은 쓰지 않는다. 엔티티 컬럼 길이는 Flyway 마이그레이션과 일치시킨다.
  - **소프트 삭제는 BaseEntity 가 `@SQLRestriction("status = 'ACTIVE'")` 로 상시 적용**한다(@MappedSuperclass 에서 전 엔티티로 상속). 따라서 모든 조회는 자동으로 `ACTIVE` 만 본다 — 리포지토리 쿼리에 별도 status 조건을 달지 않는다. 삭제는 row 제거가 아니라 `BaseEntity.delete()`(status=DELETED).
- **도메인 ↔ JPA 변환·도메인 불변 (고정).** 도메인 객체와 JPA 엔티티를 변환하는 메서드는 **JPA 엔티티 안에** 둔다 — 도메인 복원 `fun toDomain(): Domain` + `companion object { fun from(domain): Entity }`. 별도 `*Mapper` 클래스나 adapter 확장함수로 흩지 않으며, `RepositoryAdapter` 는 `Entity.from(...)`·`entity.toDomain()` 만 호출한다(도메인 클래스는 JPA 를 import 하지 않는다). **도메인 객체는 불변** — 모든 상태는 `val` 이고, 상태 변경 메서드는 변형 대신 **새 인스턴스를 반환**한다. 데이터 클래스 public `copy` 노출 대신 **`private fun copy(...)`** 를 직접 두어 통제된 복제만 허용한다. (상세·예시: [`docs/architecture/meogo-conventions.md`](docs/architecture/meogo-conventions.md) "도메인 객체 불변성 & 영속 변환".)
- **Flyway 마이그레이션 버전 규칙 (고정).** 마이그레이션 버전은 **점 구분 timestamp** `Vyyyy.MM.dd.HH.mm.ss__description.sql` 로 짓는다 — 값은 **파일 생성 시점의 로컬 현재 시각**(각 파트 두 자리 zero-pad), 예: `V2026.07.05.14.30.12__add_review_table.sql`. 병렬 브랜치에서 각자 다음 정수를 잡을 때 생기는 버전 번호 머지 충돌을 없애기 위함이다(Flyway 공식 유효 포맷 — 예시 `2013.01.15.11.35.56`). Flyway 는 버전을 숫자 파트열로 정렬한다. 기존 정수 마이그레이션(`V1`~`V10`)은 **로컬 DB 전용·프로덕션 이전 단계에서 커밋 시각 기준 timestamp 로 일괄 전환**했으므로(KB-44) 현재 모든 마이그레이션이 timestamp 포맷이다. 생성 시각 기반이라 먼저 만들고 늦게 머지된 과거 버전이 out-of-order 로 적용될 수 있어 **`spring.flyway.out-of-order=true`**(베이스 `application.yml`)를 켜 두며, 그 전제로 **각 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않게 독립적으로 작성**한다. **금지 사례**: (1) 신규에 정수 버전(`V11`) 사용, (2) **공유/프로덕션 DB 에 이미 적용된** 마이그레이션 파일 수정·리네임(checksum/history 파손 — 일괄 전환은 로컬 전용·프로덕션 이전 단계에서만 가능), (3) 순서 의존 마이그레이션 작성. (상세·근거: [`docs/architecture/meogo-conventions.md`](docs/architecture/meogo-conventions.md) "Flyway 마이그레이션 버전 규칙".)
  - **시드-동기화 테스트 ↔ 마이그레이션 파일명 결합 (주의).** 일부 테스트는 마이그레이션 SQL 을 **리소스 경로로 하드코딩해 읽는다**(예: `AvoidanceCatalogSeedSyncTest` 의 `seedResourcePath = "db/migration/…​.sql"`). 시드가 담긴 마이그레이션의 **파일명(버전)·위치를 바꾸면 그 테스트의 참조도 반드시 함께 갱신**한다 — 파일을 못 찾으면 내용이 빈 문자열로 읽혀 "파일 없음"이 아니라 **데이터 불일치 assertion 실패**로 조용히 깨진다(오진 주의). 테스트 설명(`given(...)`)에는 버전 번호를 박지 말고 버전 비의존 문구를 쓴다.

### API 응답 규약 (고정)

**모든 컨트롤러 응답 타입은 `ResponseEntity<BaseResponse<T>>`로 고정한다.** 예외 없이 모든 API는 아래 공통 봉투로 감싸 반환한다. (봉투 클래스명은 Swagger `@ApiResponse`·`ResponseEntity` 와의 혼동을 피하려고 `BaseResponse` 로 두며, 페이로드 필드는 `payload` 다.)

```kotlin
data class BaseResponse<T>(
    val success: Boolean,
    val payload: T? = null,
    val message: String? = null,
) {
    companion object {
        fun <T> ok(payload: T): BaseResponse<T> = BaseResponse(success = true, payload = payload)
        fun fail(message: String): BaseResponse<Nothing> = BaseResponse(success = false, message = message)
    }
}
```

- **성공**: `BaseResponse.ok(payload)` — `success=true`, `payload`에 페이로드, `message=null`.
- **실패**: `BaseResponse.fail(message)` — `success=false`, `payload=null`, `message`에 사유.
- 컨트롤러는 raw 도메인/DTO 를 직접 반환하지 않고 항상 `ResponseEntity<BaseResponse<T>>`로 감싼다. HTTP 상태코드는 `ResponseEntity`로, 비즈니스 성공/실패 플래그는 `BaseResponse.success`로 표현한다.
- `BaseResponse`는 모든 web 응답이 공유하므로 `:app:api`(또는 공통 web 계층)에 둔다. 페이로드 타입 `T`는 각 API 의 응답 DTO 다.

### API 엔드포인트 경로 규약 (고정)

**모든 컨트롤러 경로는 `/api/{버전}` 으로 시작한다.** 예외 없이 버전 prefix 와 함께 노출한다(예: `POST /api/v1/scans`, `GET /api/v1/foods/detail`).

- 버전 베이스는 `com.meogo.app.api.common.ApiPaths` 의 상수로 **단일 출처** 관리한다(`const val V1 = "/api/v1"`). 컨트롤러는 이 상수에 리소스 경로만 이어 붙인다 — `@RequestMapping(ApiPaths.V1 + "/scans")`. 경로 문자열에 `/api/v1` 을 직접 하드코딩하지 않는다.
- 새 버전 도입 시 `ApiPaths` 에 상수 추가(예: `const val V2 = "/api/v2"`)하고 해당 버전 컨트롤러가 참조한다. 같은 리소스의 v1·v2 컨트롤러는 서로 다른 베이스를 써 **공존**한다(기존 버전 경로는 깨지 않는다).
- 이 규약은 **비즈니스 API(`com.meogo.app.api` 컨트롤러)** 에만 적용한다. actuator·springdoc(Swagger UI) 등 프레임워크 경로는 규약 밖이며 자체 경로를 유지한다.
- 경계 강제는 후속 ArchUnit(또는 매핑 검사 테스트)로 둔다 — 모든 컨트롤러 매핑이 `/api/v` 로 시작하는지 검증.

### 인증 파라미터 애너테이션 위치 (고정)

**인증 리졸버 애너테이션(`@AuthMemberId`·`@AuthMemberIdOrNull`)은 swagger `*Api` 인터페이스가 아니라 구현 컨트롤러 클래스의 파라미터에 선언한다.** Spring 은 인터페이스 선언도 병합해 해석하지만(HandlerMethodParameter), 개발자가 컨트롤러 파일만 열어 그 엔드포인트의 인증 방식(강제/선택/없음)을 즉시 파악할 수 있어야 한다 — 인터페이스에만 두면 컨트롤러에선 평범한 `memberId: Long?` 로 보여 오독한다. 인터페이스 쪽 파라미터는 애너테이션 없이 타입만 맞춘다(중복 선언 금지 — 두 곳이 어긋나면 어느 쪽이 진실인지 모호해진다). swagger 문서 노출은 `OpenApiConfig` 의 `SpringDocUtils.addAnnotationsToIgnore` 가 두 애너테이션을 숨기므로 `@Parameter(hidden = true)` 를 따로 달지 않는다. (web 바인딩 `@RequestBody`/`@PathVariable`/`@RequestParam` 과 swagger 문서 애너테이션은 기존대로 인터페이스에 두는 관례 유지.)

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/kb-134-architecture-simplification/plan.md` (KB-134 아키텍처 단순화 — 클린아키텍처 ports&adapters 폐기(KB-101 흡수), 순수 구조 리팩토링·API/스키마 무변경. **:infra:persistence 해체** — 엔티티·Spring Data 리포지토리를 각 도메인 모듈로 이동(internal), 어댑터·리포지토리 port 6종(MemberRepository·FoodRepository·FoodScoringSource·AvoidanceSubstanceRepository·ScanHistoryRepository·RefreshTokenStore 인터페이스) 폐기, 도메인별 **도메인 서비스(MemberService·FoodService 등) 하나가 유일 public 창구**(경계는 Kotlin internal + 컴파일러 강제). RefreshTokenStore 는 :domain:member 의 Redis 구체 클래스로 이름 승계(data-redis 의존 이동). **모듈 리네임**: core/→domain/(`com.meogo.domain.<d>`), :core→:core(`com.meogo.core`) — BaseEntity·EntityStatus 를 :core 로(jakarta/hibernate compileOnly + Boot BOM), Testcontainers 공통 설정은 :core testFixtures 로. **JPA 연관관계 전면 제거** — 유일 대상 FoodJpaEntity @OneToMany(cascade) → FoodService 의 명시적 자식 save/delete + id 일괄 조회로 전환, 참조는 **값 클래스 FoodId·MemberId(:core, @JvmInline) + IdConverter base + 타입별 @Converter(autoApply)**(자기 PK 는 BaseEntity Long 유지). FK 제약은 스키마에 전부 기존재(fk_fas_*·fk_scan_history_*) — 신규 마이그레이션 0건 예상. **테스트 전략(사용자 결정: mockk 미도입)** — 페이크 port 유스케이스 단위 테스트 폐기, 시나리오를 (1) 도메인 모듈 통합 테스트(구 *RepositoryAdapterTest → <도메인>ServiceTest, MySQL Testcontainers) (2) app:api MockMvc 통합 테스트로 1:1 승계(유실 방지 매핑표를 tasks 에서 작성), 순수 로직 단위 테스트·LLM/auth seam 페이크는 유지(ScannedNameInterpreter·SocialTokenVerifier 등 비-리포지토리 seam 은 폐기 대상 아님, :infra:llm 무변경). **빌드**: meogo.domain-conventions 에 kotlin-spring·kotlin-jpa·Boot BOM·data-jpa(implementation)·api(:core) 추가(research/review 도 동일 적용), 부트앱 runtimeOnly 조립 제거, 카탈로그 spring-boot-starter-data-mongodb 삭제. **MongoDB 잔재 제거**(yml 블록 8곳·docker-compose mongo·prod exclude·카탈로그 — 코드 사용처 0건). **ArchUnit 전면 재작성**(Test-First 진입점: 새 규칙 Red → 이동 Green): 도메인 간 의존 금지·@Entity 는 domain 에만·연관관계 애너테이션 금지·컨트롤러 /api/v·app:api 엔티티 미참조. **헌법 개정이 첫 산출물** — 원칙 III·IV 대체(MAJOR v3.0.0) + ADR-0011(ADR-0006·0008 supersede) + CLAUDE.md 갱신. 기존 마이그레이션 파일 이동·리네임 금지(시드 동기화 테스트 결합). 선행 관계: 리뷰 기능(KB-128·129·131)보다 먼저.)

이전 플랜: `specs/kb-123-member-ranking/plan.md` (KB-123 회원 랭킹 — 활동량을 점수로 환산해 7단계 등급으로 매핑하고 두 곳에서 노출: 기존 `GET /api/v1/members/me/profile` 응답에 **랭킹 요약**(tier·level·score·nextTier·pointsToNext) 추가 → 프로필 탭이 호출 1회로 그려짐, 신규 `GET /api/v1/members/me/ranking` 이 요약 + **breakdown**(reviews·diversity·scans 각 count·points) 제공. 공식 `score = 리뷰 수 × 10 + 고유 음식 수 × 5 + 스캔 수 × 2`, 등급 진입점 newcomer 0 · taster 30 · explorer 80 · regular 180 · gourmet 350 · kfood_master 600 · korean_at_heart 1000(경계값은 상위 등급, 최고 등급이면 nextTier·pointsToNext = null). 서버는 **tier 안정 키만** 내려주고 번역명은 만들지 않는다(FE i18n). **랭킹은 `Member` 애그리거트의 하위 개념** — 별도 카운터 테이블·리포지토리를 두지 않고 `member` 행에 **정책 카운트 3종 컬럼**(`scan_count`·`review_count`·`unique_reviewed_food_count`, Flyway 1건, 모두 `DEFAULT 0`)을 두고 `Member.signUp` 이 0으로 초기화, `Member` 는 하위 도메인 값 객체 **`Ranking`**(`:domain:member` — 카운트 3종 + 점수·등급·항목별 점수 파생, `RankingTier` 와 짝)을 `val ranking` 으로 **읽기 전용** 소유한다. 카운트업은 도메인이 아니라 **DB 원자적 증가** — `MemberRepository.increaseScanCount(memberId)` port + JPQL `update member set scan_count = scan_count + 1`(활성 회원만, 0행이면 `MEMBER_NOT_FOUND`). 읽고-더해-쓰면 동시 스캔에서 lost update 가 나므로 금지하며, 같은 이유로 `MemberJpaEntity.applyDomain`(프로필 저장)은 카운트 컬럼을 쓰지 않는다(오래된 스냅샷이 카운트를 되돌리지 못하게). 탈퇴 시 회원과 함께 사라지고 가입 초기화도 공짜다. **스캔 횟수 = 메뉴판 1장 1회** — `scan_history` 는 매칭 음식마다 행이 생겨 횟수 집계에 못 쓴다. `ScanUseCase.assessMenuBoard` 가 스캔마다 회원 로드 → `recordScan()` → `MemberRepository.update`(매칭 0건이어도 1회). 점수·등급은 저장하지 않고 조회 시점 계산이라 배점·등급표가 바뀌어도 재계산 배치가 없다. **리뷰 도메인(`:domain:review`)은 빈 placeholder 라 리뷰 카운트 2종은 컬럼만 있고 0에 머문다** — 리뷰 기능 도입 시 카운트업 호출만 추가하면 되고 마이그레이션·도메인·응답 계약은 무변경(고유 음식 수는 그 음식의 첫 리뷰일 때만 올린다). `MemberRankingUseCase` 는 경계 DTO `MemberRankingResult` 반환(app:api 가 도메인 타입 미참조), `MemberProfileUseCase.getMyProfile` 이 이를 호출해 요약을 얹는다(산정 경로 단일화 → 요약↔상세 불일치 원천 차단). 인증 설정 무변경(`/api/v1/members/*` 는 JWT 필터가 이미 덮음). 신규 리포지토리·엔티티 없음 — `MemberJpaEntity.applyDomain` 이 scan_count 왕복까지 처리. 테스트: `MemberRankingTest`(공식·경계 전수·최고 등급 null·검증 케이스 128/explorer/52) + `MemberTest`(가입 시 0·recordScan 불변·프로필 갱신 시 보존) + `MemberRepositoryAdapterTest`(scan_count 영속) + `ScanUseCaseHistoryTest`(메뉴판 1장=1회) + `MemberRankingUseCaseTest` + `MemberControllerTest`(가입 직후 0점·프로필 랭킹 요약·상세 breakdown·401·요약↔상세 일치·최고 등급 null). **기존 회원 스캔 횟수는 소급되지 않는다**(배포 이후 누적). 병렬 작업 KB-124(프로필 부분 수정)가 `MemberProfileUseCase`·`MemberController`·`MemberControllerTest` 를 함께 건드리므로 먼저 머지된 쪽 기준 리베이스. FE 공유 필요: 정책 문서는 랭킹을 상세 엔드포인트에서만 받는 전제였다.)

<!-- SPECKIT END -->
