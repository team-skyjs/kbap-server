# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 개요

`meogo/meogo-server` — Kotlin으로 작성된 Spring Boot 백엔드. Gradle 멀티모듈 구조다. **실행 가능한 bootJar 는 두 개** — `:meogo-api:presentation`(web, 진입점 `com.meogo.api.MeogoApiApplication`)와 `:meogo-batch`(배치, 진입점 `com.meogo.batch.MeogoBatchApplication`)다. `meogo-api`는 **컨테이너 폴더**이고 그 안에 web(`presentation`)/`application`/`infra`/`core` + 도메인 컨텍스트(`food`/`member`/`scan`/`assessment`/`research`, deferred placeholder `review`) leaf 모듈이 **평탄하게**(`meogo-domain` 중첩 없이) 들어간다. `meogo-batch`는 `:meogo-api:application` 유스케이스를 트리거하는 위성 앱이다(특히 `research` 미스 메뉴 조사·종합을 하루 1회 트리거 — ADR-0003·0004). `meogo-common`은 두 앱이 공유하는 통합 이벤트·DTO·기술 공통 모듈이다. 공통 빌드 설정은 `buildSrc` 컨벤션 플러그인에 둔다. 아직 비즈니스 코드는 거의 비어 있는 스캐폴드 상태다.

### 모듈 구조

상세 책임·경계는 [`docs/architecture/meogo-api-module-structure.md`](docs/architecture/meogo-api-module-structure.md) 참고. 의존 방향은 단방향으로 고정한다.

```
[meogo-api 앱]
core ← 도메인(food/member/scan/assessment/research/review) ← application ← api (bootJar)
  ↑                                                     │ runtimeOnly(조립)
  └──────────────────────── infra ←────────────────────┘

[meogo-batch 앱]  :meogo-api:application 호출 + :meogo-api:infra 조립(runtimeOnly)
[meogo-common]    meogo-api·meogo-batch 공유 (통합 이벤트·DTO·기술 공통)
```
(infra 는 core 의 port 에만 의존하고, port 구현체는 조립 모듈 `:meogo-api:presentation`·`:meogo-batch`가 런타임에 주입한다.)

- `:meogo-api:presentation`: web bootJar — controller, API DTO. **조립 책임** — `:meogo-api:infra`를 `runtimeOnly`로 결합해 adapter 빈을 런타임 DI 로 연결한다(컴파일 의존 X). DB 마이그레이션(Flyway) **스키마 owner** — 마이그레이션 파일은 `src/main/resources/db/migration`.
- `:meogo-api:application`: 유스케이스 조율, transaction boundary. 외부 client 는 **`:meogo-api:core`의 port 인터페이스로만** 사용하고 infra 구현체에는 직접 의존하지 않는다(계층 역전 방지). **컨텍스트 간 조합은 여기서만** 일어난다.
- 도메인 컨텍스트 모듈: `meogo-api` 컨테이너 직속으로 **평탄화** — active: `:meogo-api:{food,member,scan,assessment,research}`, deferred placeholder: `:meogo-api:review`. 각 도메인은 `:meogo-api:core`만 바라보며 JPA/Mongo 영속성을 자기 모듈 안에 숨긴다. (`research`는 미스 메뉴 조사·종합, 배치 전용 — web 미노출.)
- `:meogo-api:core`: 공통 타입·예외·이벤트 계약·유틸, 외부 client **port 인터페이스**. **Spring-free.**
- `:meogo-api:infra`: LLM 등 외부 API/메시지큐/이벤트 어댑터. `:meogo-api:core`의 port 를 **구현**하며, `:meogo-api:presentation`·`:meogo-batch`가 런타임에 주입한다.
- `:meogo-batch`: 배치 bootJar(단일 모듈, 추후 필요 시 분리). `:meogo-api:application`을 트리거하고 `:meogo-api:infra`를 `runtimeOnly`로 조립한다. **flyway off**(스키마 owner 는 api — 중복 적용 방지).
- `:meogo-common`: 통합 이벤트·공통 DTO·기술 공통(logback 조각·유틸·횡단 어노테이션). `meogo-api`·`meogo-batch` 공유. **web/jpa/도메인 의존 금지**(가볍게 유지 → 디커플드 컨슈머도 안전). **Spring-free.** 통합 이벤트는 도메인 타입을 참조하지 않고 평면 값(ID·코드·스냅샷)만 담는다. (in-process 도메인 이벤트는 core/domain, 브로커 타는 통합 이벤트는 common.)

각 모듈은 `src/main`·`src/test` 소스셋을 모두 가진다. **모듈 간 project 의존은 `api`가 아니라 `implementation`을 기본으로** 한다 — `:meogo-api:application`은 도메인/코어 모듈을 `implementation`으로 의존하므로 도메인 타입이 `:meogo-api:presentation`의 **컴파일 클래스패스로 전이되지 않는다**(런타임에만 전이되어 빈·컴포넌트 스캔·JPA는 정상). 따라서 api 는 application 의 공개 타입(Command/Result 등)만 보고, JPA Entity·Spring Data Repository·도메인 엔티티를 직접 import 할 수 없다. 이 경계는 추후 ArchUnit 테스트로 강제한다.

## 설계 / 문서 위치

- **백엔드 아키텍처**(DDD·바운디드 컨텍스트·모듈 구성·데이터/AI 파이프라인) → [`docs/architecture/`](docs/architecture/). 강제 규칙은 `docs/architecture/meogo-conventions.md`.
- **의사결정 기록(ADR)** → [`docs/adr/`](docs/adr/). SpecKit 사이클마다 중요한 결정을 남긴다.
- **구현 설계**(기능별 "어떻게") → SpecKit `specs/NNN-slug/`(spec·plan·tasks). 교차-컨텍스트 흐름은 `mermaid-flows` 스킬로 시퀀스 다이어그램을 그린다.
- **제품 개요·기획 PRD("무엇을/왜")** → 공유 허브 `agent-hub/`(이 repo에선 git-ignored, 별도 서브모듈로 관리). 구현 세부는 여기 두지 않는다.

## 기술 스택

- **Kotlin 2.3** / **JVM (Java 21 toolchain)** — Gradle toolchain이 JDK를 해석하므로 로컬 `JAVA_HOME`에 묶이지 않는다(`settings.gradle.kts`의 foojay-resolver가 자동 프로비저닝).
- **Spring Boot 4.1** — web/validation/actuator/data-jpa/data-mongodb 스타터. 영속: **MySQL**(prod, `mysql-connector-j`) + H2(test) + MongoDB. DB 마이그레이션: **Flyway**(+flyway-mysql). API 문서: **springdoc-openapi**(Swagger UI). JWT: 구현 시 결정(아직 미추가).
- **LLM: Spring AI 2.0**(Boot 4 호환 라인) — `:meogo-api:infra`에 `spring-ai-starter-model-openai` + `spring-ai-starter-model-google-genai`. 3개 모델(OpenAI·Upstage·Gemini) 병렬 호출 설계: Upstage는 OpenAI 호환이라 openai 스타터를 base-url만 교체해 재사용, Gemini는 google-genai 스타터. 모델 빈은 LLM 구현 시 명시 구성하며, 키 없이 자동구성이 떠서 부팅이 깨지지 않도록 `application.yml`에서 `spring.ai.model.*=none`으로 기본 비활성.
- 빌드 도구: **Gradle (Kotlin DSL)**, 래퍼 사용.
- 테스트: **JUnit 5 플랫폼**(`useJUnitPlatform`) + **Kotest**(`kotest-runner-junit5` + `kotest-assertions-core`). Spring 모듈은 `spring-boot-starter-test`도 추가.

### 빌드 구성 (버전·공통 설정 관리)

> 입문 설명서: [`docs/guides/gradle-made-easy.md`](docs/guides/gradle-made-easy.md).

- **버전 카탈로그** `gradle/libs.versions.toml`이 모든 버전(라이브러리·플러그인)의 단일 출처다. `libs.*` 접근자로 참조한다. 스타터 버전은 대부분 Spring Boot BOM이 관리하므로 카탈로그에 버전을 적지 않는다.
- **공통 설정은 `buildSrc` 의 컨벤션 플러그인**(미리 컴파일된 `meogo.*.gradle.kts`)에 둔다. 각 모듈은 `plugins { id("meogo.<archetype>") }` 한 줄로 자기 아키타입을 선언한다. 루트 `build.gradle.kts`는 거의 비어 있다(집계 전용).
  - `meogo.kotlin-common` — **모든 leaf 모듈** 공통: kotlin-jvm·java-library, Java 21 toolchain, Kotlin 엄격성 플래그, `group`/`version`, 공통 테스트(Kotest + JUnit launcher + `useJUnitPlatform()`). Spring-free 모듈(core/common)은 이것만 적용.
  - `meogo.spring-conventions` — **Spring 라이브러리 공통**(core/common 제외): kotlin-common 위에 kotlin-spring·dependency-management·Spring Boot/AI BOM·`kotlin-reflect`/`jackson-module-kotlin`/`spring-boot-starter-test`를 얹는다.
  - `meogo.spring-boot-application` — **부트 앱(bootJar)**: `:meogo-api:presentation`, `:meogo-batch`. spring-conventions 위에 `org.springframework.boot`.
  - `meogo.domain-conventions` — **도메인 5종 공통**: spring-conventions 위에 `api(:meogo-api:core)` + data-jpa/mongo 은닉 + mysql runtime + h2 test. (도메인 build 파일이 한 줄로 줄어든다.)
- **모듈별 고유 설정만 각 모듈 `build.gradle.kts`** 에 둔다(api=web/validation/actuator/flyway/springdoc, infra=spring-ai, batch=application 의존+infra 조립 등). 모듈 build 파일에서 의존성은 **문자열 표기**(`"implementation"(...)`)로 적는다(플러그인이 컨벤션에서 적용돼 타입 안전 단축표기 미생성). 라이브러리 좌표는 모듈 build 파일에선 `libs.*`로 정상 사용.
- **버전 카탈로그 접근**: 컨벤션 플러그인 **안에서는** `libs.*` 타입세이프 접근자가 안 잡혀 `VersionCatalogsExtension`의 `findLibrary`/`findVersion`으로 조회한다. buildSrc 는 `buildSrc/settings.gradle.kts`에서 루트 `gradle/libs.versions.toml`을 `libs`로 가져오고, `buildSrc/build.gradle.kts`는 `libs.plugins.*`를 플러그인 마커 좌표로 변환해 서드파티 Gradle 플러그인을 classpath 에 올린다.
- **트레이드오프**: buildSrc 변경 시 전체 빌드 캐시가 무효화돼 느려질 수 있다(대신 도메인 5종 dedup·모듈 파일 슬림).

## 명령어

모든 작업은 Gradle 래퍼(`./gradlew`)로 실행한다.

```bash
./gradlew build                          # 전체 모듈 컴파일 + 테스트 + 아티팩트 생성
./gradlew :meogo-api:presentation:bootRun         # web 앱 실행 (프로필은 SPRING_PROFILES_ACTIVE 로 지정)
./gradlew :meogo-batch:bootRun           # 배치 앱 실행
./gradlew test                           # 전체 모듈 테스트 실행
./gradlew :meogo-api:presentation:test            # 특정 모듈만 테스트
./gradlew :meogo-api:presentation:test --tests "com.meogo.api.MeogoApiApplicationTests"          # 단일 테스트 클래스
./gradlew :meogo-api:presentation:test --tests "com.meogo.api.MeogoApiApplicationTests.contextLoads"  # 단일 테스트 메서드
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
- 소스는 각 모듈의 `src/main/kotlin/...`, 테스트는 `src/test/kotlin/...`에서 동일 구조로 미러링한다. **`meogo-api` 하위 모든 모듈은 패키지를 `com.meogo.api.<모듈명>` 으로 둔다** — 도메인 컨텍스트는 `com/meogo/api/<context>/`(예: `meogo-api/food/src/main/kotlin/com/meogo/api/food/` — 모듈 경로는 평탄화됐고 패키지도 `com.meogo.api.food`), 계층은 `com/meogo/api/{application,infra}/`, 커널은 `com/meogo/api/core/`, web 모듈(`presentation`)은 `com/meogo/api/presentation/`(컨트롤러·DTO·`ApiResponse`). **부트 진입점 `MeogoApiApplication`은 `com/meogo/api`** 에 두고 전 하위 패키지를 컴포넌트 스캔한다(`scanBasePackages=["com.meogo.api"]`). 배치는 `com/meogo/batch`.
- web 실행 설정은 `meogo-api/presentation/src/main/resources/`에 YAML로 둔다: 베이스 `application.yml` + 프로필별 `application-{local,dev,staging,prod}.yml`. 확장자는 `.yml`로 통일한다(`.yaml` 아님). 테스트용 오버라이드는 `meogo-api/presentation/src/test/resources/application.yml`(Flyway off, H2 `create-drop`). 배치는 `meogo-batch/src/main/resources/application.yml`(flyway off). 공통 로깅은 `meogo-common`의 `logback-common.xml`을 각 앱 `logback-spring.xml`이 `<include>`로 가져간다.
- 컴파일러 엄격성 플래그는 `buildSrc`의 `meogo.kotlin-common` 컨벤션 플러그인에서 전 모듈에 일괄 적용되며, 신규 코드도 이를 준수해야 한다:
  - `-Xjsr305=strict` — JSR-305 nullability 애너테이션을 강제 제약으로 취급(Spring/Java API 호출 시 영향).
  - `-Xannotation-default-target=param-property` — Kotlin 프로퍼티의 기본 애너테이션 타깃을 변경.
- **테스트 스타일 (고정).** **모든 테스트는 Kotest `BehaviorSpec` 으로 통일**한다(다른 Spec 스타일·JUnit `@Test` 금지). 구조는 **`given("대상/전제") > \`when\`("상황") > then("기대 결과")`** 를 기본으로 하며, `given`·`` `when` ``·`then` 설명은 **한국어**로 쓴다(예: `` given("BoundingBox 생성") { `when`("x 가 음수이면") { then("예외를 던진다") { ... } } } ``).
  - **Spring 통합 테스트**(`@SpringBootTest`·MockMvc·repository)도 `BehaviorSpec` 으로 작성한다. `kotest-extensions-spring`(`io.kotest.extensions.spring.SpringExtension`)을 써서 클래스 본문 스타일(`class Foo : BehaviorSpec() { override fun extensions() = listOf(SpringExtension); @Autowired lateinit var ...; init { given... } }`)로 빈을 주입한다. `SpringExtension` 의존성은 `meogo.spring-conventions` 컨벤션 플러그인이 전 Spring 모듈 테스트에 일괄 제공한다.
  - MockMvc 는 `@AutoConfigureMockMvc` + `@Autowired MockMvc` 로 주입한다(`ObjectMapper` 빈은 주입 안 되므로 `jacksonObjectMapper()` 로 직접 생성).
- **JPA 연관관계 로딩 (고정).** 모든 연관관계(`@OneToMany`·`@ManyToOne`·`@OneToOne`·`@ManyToMany`)는 **`FetchType.LAZY`** 로 작성한다(`@ManyToOne`·`@OneToOne` 의 기본값 EAGER 도 명시적으로 LAZY 로 덮는다). 애그리거트 전체나 특정 연관을 함께 로드해야 하면 **fetch join 쿼리**(`@Query("… left join fetch …")`)로 명시적으로 가져온다 — EAGER 매핑으로 해결하지 않는다(N+1·불필요 로딩·`LazyInitializationException` 방지). 영속 어댑터가 트랜잭션 밖에서 도메인 매핑 시 컬렉션을 접근하면 fetch join 으로 미리 초기화한다.
- **JPA 엔티티 작성 (고정).** 엔티티는 `:meogo-api:<도메인>` 의 `infrastructure` 패키지에 은닉한다. `kotlin-jpa`(no-arg) 플러그인이 `domain-conventions` 로 적용되므로 **프로퍼티 기본값으로 no-arg 를 흉내내지 않아도 된다**. JPA 애너테이션은 **use-site 타깃 없이**(`@Id`/`@Column`, `@field:` 불필요 — field-only 타깃이라 자동으로 field 에 적용) 단다.

### API 응답 규약 (고정)

**모든 컨트롤러 응답 타입은 `ResponseEntity<ApiResponse<T>>`로 고정한다.** 예외 없이 모든 API는 아래 공통 봉투로 감싸 반환한다.

```kotlin
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
) {
    companion object {
        fun <T> ok(data: T): ApiResponse<T> = ApiResponse(success = true, data = data)
        fun fail(message: String): ApiResponse<Nothing> = ApiResponse(success = false, message = message)
    }
}
```

- **성공**: `ApiResponse.ok(data)` — `success=true`, `data`에 페이로드, `message=null`.
- **실패**: `ApiResponse.fail(message)` — `success=false`, `data=null`, `message`에 사유.
- 컨트롤러는 raw 도메인/DTO 를 직접 반환하지 않고 항상 `ResponseEntity<ApiResponse<T>>`로 감싼다. HTTP 상태코드는 `ResponseEntity`로, 비즈니스 성공/실패 플래그는 `ApiResponse.success`로 표현한다.
- `ApiResponse`는 모든 web 응답이 공유하므로 `:meogo-api:presentation`(또는 공통 web 계층)에 둔다. 페이로드 타입 `T`는 각 API 의 응답 DTO 다.

### API 엔드포인트 경로 규약 (고정)

**모든 컨트롤러 경로는 `/api/{버전}` 으로 시작한다.** 예외 없이 버전 prefix 와 함께 노출한다(예: `POST /api/v1/menu-scans`, `GET /api/v1/foods/detail`).

- 버전 베이스는 `com.meogo.api.presentation.common.ApiPaths` 의 상수로 **단일 출처** 관리한다(`const val V1 = "/api/v1"`). 컨트롤러는 이 상수에 리소스 경로만 이어 붙인다 — `@RequestMapping(ApiPaths.V1 + "/menu-scans")`. 경로 문자열에 `/api/v1` 을 직접 하드코딩하지 않는다.
- 새 버전 도입 시 `ApiPaths` 에 상수 추가(예: `const val V2 = "/api/v2"`)하고 해당 버전 컨트롤러가 참조한다. 같은 리소스의 v1·v2 컨트롤러는 서로 다른 베이스를 써 **공존**한다(기존 버전 경로는 깨지 않는다).
- 이 규약은 **비즈니스 API(`com.meogo.api.presentation` 컨트롤러)** 에만 적용한다. actuator·springdoc(Swagger UI) 등 프레임워크 경로는 규약 밖이며 자체 경로를 유지한다.
- 경계 강제는 후속 ArchUnit(또는 매핑 검사 테스트)로 둔다 — 모든 컨트롤러 매핑이 `/api/v` 로 시작하는지 검증.

<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan:
`specs/001-menu-scan-mock/plan.md` (메뉴 스캔 제출·판정 & 음식 상세 조회 — mock 슬라이스).
<!-- SPECKIT END -->
