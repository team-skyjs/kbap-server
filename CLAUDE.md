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

`kbap/kbap-server` — Kotlin으로 작성된 Spring Boot 백엔드. Gradle 멀티모듈 **모듈러 모놀리스**다. **실행 가능한 bootJar 는 두 개** — `:api`(web, 진입점 `com.kbap.KbapApiApplication`)와 `:batch`(배치, 진입점 `com.kbap.batch.KbapBatchApplication`)다.

**2026-07-28 모듈 다이어트(KB-244, ADR-0016)** 이후 애플리케이션 모듈은 `:common`·`:api`·`:batch` 3개 + 인프라 어댑터 4종(`:infra:*`)이다. 모듈 디렉터리는 **루트 직속**(`common/`·`api/`·`batch/`)이고 껍데기 컨테이너를 두지 않는다 — 유일한 컨테이너 `infra/` 는 어댑터가 4종이라 남긴다. **패키지는 모듈명을 그대로 미러링**한다(`:api`→`com.kbap.api`, `:batch`→`com.kbap.batch`, `:common`→`com.kbap.common`). API 모듈은 ADR-0017에 따라 `com.kbap.api.<feature>` 기능 패키지로 평탄화하며 `com.kbap.domain`·`com.kbap.application`을 쓰지 않는다. JPA 엔티티가 곧 도메인 모델이고(별도 도메인 모델·toDomain/from 변환 없음), **비즈니스 로직은 `common.domain`의 도메인 서비스·모델이 소유**한다. 도메인 경계는 모듈이 아니라 **패키지 + ArchUnit**(도메인 간 허용 방향 맵 — `ModuleBoundaryTest`)이 긋는다. 리포지토리·엔티티는 **public**(KB-220, ADR-0014) — 도메인 로직은 도메인 서비스를 경유하고, 단순 영속 접근은 소비 계층이 리포지토리를 직접 쓴다(위임 전용 창구 서비스 금지). 외부 시스템(jjwt·firebase·Redis·LLM·S3)은 **인터페이스(seam)는 `:common` 에, 구현은 `:infra:*` 에, 조립은 부트앱 config** 패턴으로 격리한다. DB 는 공유(batch `flyway off`, 스키마 owner=api). 공통 빌드 설정은 `buildSrc` 컨벤션 플러그인에 둔다.

### 모듈 구조

모듈 의존은 단방향으로 고정한다: **api·batch·infra → `:common`**, 그리고 **부트앱 → `:infra:*`(어댑터 조립 전용)**. api 와 batch 는 서로를 모른다.

```
:common  ← :api   (web bootJar, Flyway owner — 기능별 HTTP 경계·유스케이스 조합)
         ← :batch (배치 bootJar, flyway off — common 의 레포·클라이언트를 직접 조립)
         ← :infra:llm / :infra:auth / :infra:redis / :infra:storage
            (외부 시스템 구현체 — common 의 seam 인터페이스를 구현)
:infra:* ← 부트앱 (조립: api → auth·storage(implementation)+llm·redis(runtimeOnly), batch → llm·storage.
            infra 클래스 직접 참조는 조립 config 패키지에서만 — ArchUnit 강제)
```

- `:common`: **영속 계층 전부 + api 밖(배치·인프라 어댑터)이 컴파일 의존하는 코드**. 패키지 루트 `com.kbap.common`:
  - `common.core` — 도메인 소속이 아닌 공통뿐: **통합 에러**(`ErrorCode` + `BusinessException`)·테스트픽스처(`testsupport` — Testcontainers). Spring-free 유지.
  - `common.domain.<context>` — **전 컨텍스트의 엔티티(`model/`)와 리포지토리**. web·batch 공유 도메인(food·member·avoidance)은 도메인 서비스·`dto/`까지 여기 있고, scan·bookmark·image·metering은 영속을 소유한다. 미터링 이벤트 vocabulary(`LlmCallCostIncurred`)는 `common.domain.metering` 소속.
  - `common.util` — 순수 유틸(`ImageUrls`·`KoreanMenuNameNormalizer`). Spring-free.
  - `common.port.{llm,storage,auth}` — **infra 계약(seam) 전부**, 구현 모듈 기준으로 분류: llm(`MenuBoardVisionExtractor`·`Food*Client` 4종), storage(`StorageObjectStore`·`PresignedUploadPort`), auth(`TokenIssuer`·`TokenParser`·`SocialTokenVerifier`·`RefreshTokenStore`·`SocialAccountDeleter`). 설정 홀더는 계약이 아니다 — jwt 설정(`JwtTokenProperties`)은 `infra:auth` 소속. 순수 계약 — Spring·JPA 를 모른다(ArchUnit 강제). 포트가 도메인 타입을 반환하는 방향은 허용, 도메인이 포트를 아는 역방향은 금지.
- `:api`: web bootJar — `com.kbap.api.<feature>`에 controller·API DTO(`*Request`/`*Response`)·API 전용 서비스·결과 타입을 기능별로 함께 두고, `config/`가 빈을 조립한다. `com.kbap.domain`·`com.kbap.application` 계층 패키지는 사용하지 않는다(ADR-0017). 진입점 `KbapApiApplication`은 `com.kbap` 루트(스캔이 전 계층 커버). DB 마이그레이션(Flyway) **스키마 owner** — `src/main/resources/db/migration`.
- `:batch`: 배치 bootJar. **컴포넌트 스캔을 자신 + `com.kbap.infra.llm` 로 좁힌다** — 도메인 서비스 그래프(외부 seam 필요)를 올리지 않고, common 의 리포지토리를 직접 주입해 조립한다(트랜잭션 경계는 배치가 `TransactionTemplate` 로 소유). 엔티티/레포 스캔은 `@AutoConfigurationPackage("com.kbap")`. **flyway off**(스키마 owner=api). 패키지 `com.kbap.batch`.
- `:infra:llm`(Spring AI — ADR-0010)·`:infra:auth`(jjwt+firebase-admin)·`:infra:redis`(`RedisRefreshTokenStore`)·`:infra:storage`(S3 — presign·head/delete): common 의 seam 구현체. 전부 `:common` 만 의존하고, 부트앱이 `implementation`/`runtimeOnly` 로 골라 조립한다.

도메인 패키지는 `com.kbap.common.domain.<context>`로 일원화한다 — **엔티티(=도메인 모델, 도메인 메서드 내장) + 리포지토리(public)**를 소유하고, web·batch가 공유하는 비즈니스 로직은 도메인 서비스가 소유한다. API 앱만 소비하는 요청 조합·외부 seam 호출·응답 조립은 `com.kbap.api.<feature>`에 둔다. 도메인 간 의존은 단방향만 허용하며 `ModuleBoundaryTest`가 `common.domain` 패키지 간 방향을 검사한다. API 기능 패키지는 컨트롤러와 유스케이스 조합을 함께 가지므로 이 도메인 맵의 대상이 아니다.

`:common`→data-jpa 는 `api`(엔티티가 서비스 시그니처에 노출), 그 외 모듈 간 의존은 `implementation` 기본. 경계 강제는 — **Gradle**(모듈 간: api↔batch 상호 참조·infra→app 역참조 차단), **ArchUnit**(`ModuleBoundaryTest.kt`, Kotest 태그 `arch` — `common.domain` 간 허용 방향 맵·커널 Spring-free·도메인→api/infra 금지·`@Entity` 위치·컨트롤러 `/api/v`·API 구 패키지 금지). 도메인 로직의 도메인 서비스 소유는 규율·리뷰로 지킨다(ADR-0014).

## 설계 / 문서 위치

- **백엔드 아키텍처**(DDD·바운디드 컨텍스트·모듈 구성·데이터/AI 파이프라인) → [`docs/architecture/`](docs/architecture/). 강제 규칙은 `docs/architecture/meogo-conventions.md`.
- **의사결정 기록(ADR)** → [`docs/adr/`](docs/adr/). SpecKit 사이클마다 중요한 결정을 남긴다.
- **구현 설계**(기능별 "어떻게") → SpecKit `specs/NNN-slug/`(spec·plan·tasks). 교차-컨텍스트 흐름은 `mermaid-flows` 스킬로 시퀀스 다이어그램을 그린다.
- **프로젝트 도메인 지식·데일리 작업 로그** → 지식 위키 `../kbap-agenthub/`(kbap·kbap-langchain 공유, 독립 repo). 아래 "지식 위키" 섹션 참조.

## 지식 위키 (kbap-agenthub)

- 위치: `../kbap-agenthub` — kbap·kbap-langchain 이 공유하는 지식 위키(독립 repo). 도메인 맥락이 필요하면 **`../kbap-agenthub/INDEX.md`(색인)를 먼저 읽고** 필요한 문서만 골라 읽는다 — 위키 본문을 통째로 로드하지 않는다.
- **자동 축적 (작업 중 상시)**: 코드로 알 수 없는 도메인 지식·중요 결정이 나오면 `../kbap-agenthub/wiki/<kebab-case-topic>.md` 에 기록하고 `INDEX.md` 에 한 줄 추가 후 허브에서 커밋. 기록 규칙 상세는 허브의 `CLAUDE.md`. (데일리 작업 요약은 `/clear` 시 SessionEnd 훅이 자동 기록 — 세션 중 신경 쓰지 않는다.)

## 기술 스택

- **Kotlin 2.3** / **JVM (Java 21 toolchain)** — Gradle toolchain이 JDK를 해석하므로 로컬 `JAVA_HOME`에 묶이지 않는다(`settings.gradle.kts`의 foojay-resolver가 자동 프로비저닝).
- **Spring Boot 4.1** — web/validation/actuator/data-jpa/data-redis 스타터. 영속: **MySQL**(prod, `mysql-connector-j`, 통합 테스트는 MySQL Testcontainers) + **Redis**(refresh token — KB-118). DB 마이그레이션: **Flyway**(+flyway-mysql). API 문서: **springdoc-openapi**(Swagger UI). 인증: 자체 JWT(jjwt) + Firebase ID 토큰 검증(firebase-admin) — 구현은 `:infra:auth`, refresh token 저장은 `:infra:redis`.
- **LLM: Spring AI 2.0**(Boot 4 호환 라인) — 전용 모듈 **`:infra:llm`**(ADR-0010)에 `spring-ai-starter-model-openai` 하나(비전 `OpenAiChatModel`·임베딩 `OpenAiEmbeddingModel` — 이미지 배치만 OpenAI Batch API 직접 호출). seam 은 `:common` 의 `common.port.llm` 3종(`MenuBoardVisionExtractor`·`FoodImageBatchClient`·`TextEmbeddingClient`)뿐이고 구현·구성이 이 모듈에 응집된다. **소비자는 `:api`·`:batch`(벡터 동기화, KB-328)**다 — 음식 콘텐츠 채움이 kbap-langchain 으로 이관되면서(KB-301) 배치용 채팅 caller·fan-out·다중 벤더가 전부 제거됐다(KB-320). `kbap.llm.*` 프로퍼티 + `@ConditionalOnProperty`로 명시 구성한다: `vision`(메뉴판 스캔, `gpt-5.6-luna`)·`image`(`gpt-image-2` 배치 이미지 생성)·`embedding`(OpenAI `text-embedding-3-small` 256차원 — KB-328 에서 Bedrock Titan 전환, `OPENAI_API_KEY` 재사용). api 는 `vision.enabled: true` 고정 — 스캔이 필수 기능이라 `OPENAI_API_KEY` 없이는 부팅하지 않는다. vision 전용 환경변수는 없다(사진 fetch 도메인은 `kbap.storage.public-base-url` 을 참조). Spring AI 자동구성 유입은 `application.yml`의 `spring.ai.model.*=none`으로 차단.
- 빌드 도구: **Gradle (Kotlin DSL)**, 래퍼 사용.
- 테스트: **JUnit 5 플랫폼**(`useJUnitPlatform`) + **Kotest**(`kotest-runner-junit5` + `kotest-assertions-core`). Spring 모듈은 `spring-boot-starter-test`도 추가.

### 빌드 구성 (버전·공통 설정 관리)

> 입문 설명서: [`docs/guides/gradle-made-easy.md`](docs/guides/gradle-made-easy.md).

- **버전 카탈로그** `gradle/libs.versions.toml`이 모든 버전(라이브러리·플러그인)의 단일 출처다. `libs.*` 접근자로 참조한다. 스타터 버전은 대부분 Spring Boot BOM이 관리하므로 카탈로그에 버전을 적지 않는다.
- **공통 설정은 `buildSrc` 의 컨벤션 플러그인**(미리 컴파일된 `kbap.*.gradle.kts`)에 둔다. 각 모듈은 `plugins { id("kbap.<archetype>") }` 한 줄로 자기 아키타입을 선언한다. 루트 `build.gradle.kts`는 거의 비어 있다(집계 전용).
  - `kbap.kotlin-common` — **모든 leaf 모듈** 공통: kotlin-jvm·java-library, Java 21 toolchain, Kotlin 엄격성 플래그, `group`/`version`, 공통 테스트(Kotest + JUnit launcher + `useJUnitPlatform()` + `-Dkotest.tags` 전달). Spring-free 모듈은 이것만 적용. 
  - `kbap.spring-conventions` — **Spring 라이브러리 공통**(infra 4종): kotlin-common 위에 kotlin-spring·dependency-management·Spring Boot/AI BOM·`kotlin-reflect`/`jackson-module-kotlin`/`spring-boot-starter-test`를 얹는다.
  - `kbap.spring-boot-application` — **부트 앱(bootJar)**: `:api`, `:batch`. spring-conventions 위에 `org.springframework.boot`.
  - `kbap.common-conventions` — **`:common` 전용**: kotlin-common 위에 kotlin-spring·**kotlin-jpa(no-arg)**·dependency-management·Boot BOM·`api(data-jpa)`·`implementation(kotlin-reflect·jackson-module-kotlin)`·`runtimeOnly(mysql)`·`java-test-fixtures`(Testcontainers 공통 설정)·테스트 공통을 얹는다. (`:api` 는 도메인 엔티티를 품으므로 kotlin-jpa·allOpen 을 자체 build 파일에 추가로 적용한다.)
- **모듈별 고유 설정만 각 모듈 `build.gradle.kts`** 에 둔다(api=web/validation/actuator/flyway/springdoc+kotlin-jpa, infra:llm=spring-ai, batch=common·llm·storage 의존 등). 모듈 build 파일에서 의존성은 **문자열 표기**(`"implementation"(...)`)로 적는다(플러그인이 컨벤션에서 적용돼 타입 안전 단축표기 미생성). 라이브러리 좌표는 모듈 build 파일에선 `libs.*`로 정상 사용.
- **버전 카탈로그 접근**: 컨벤션 플러그인 **안에서는** `libs.*` 타입세이프 접근자가 안 잡혀 `VersionCatalogsExtension`의 `findLibrary`/`findVersion`으로 조회한다. buildSrc 는 `buildSrc/settings.gradle.kts`에서 루트 `gradle/libs.versions.toml`을 `libs`로 가져오고, `buildSrc/build.gradle.kts`는 `libs.plugins.*`를 플러그인 마커 좌표로 변환해 서드파티 Gradle 플러그인을 classpath 에 올린다.
- **트레이드오프**: buildSrc 변경 시 전체 빌드 캐시가 무효화돼 느려질 수 있다(대신 아키타입 dedup·모듈 파일 슬림).

## 명령어

모든 작업은 Gradle 래퍼(`./gradlew`)로 실행한다.

```bash
./gradlew build                          # 전체 모듈 컴파일 + 테스트 + 아티팩트 생성
./gradlew :api:bootRun         # web 앱 실행 (프로필은 SPRING_PROFILES_ACTIVE 로 지정)
./gradlew :batch:bootRun           # 배치 앱 실행
./gradlew test                           # 전체 모듈 테스트 실행
./gradlew :api:test            # 특정 모듈만 테스트
./gradlew :api:test --tests "com.kbap.api.KbapApiApplicationTests"          # 단일 테스트 클래스
./gradlew clean                          # 빌드 산출물 정리
```

실행 프로필은 `local`/`dev`/`staging`/`prod` 4종이다(`SPRING_PROFILES_ACTIVE`로 선택). 통합 테스트(`@SpringBootTest`)는 MySQL Testcontainers(`:common` testFixtures 의 `MySqlContainerConfig`, `@ServiceConnection`)로 동작한다(KB-46 — H2 미사용). ArchUnit 스펙만 제외하려면 `-Dkotest.tags="!arch"`.

별도 lint 태스크는 설정되어 있지 않으며, Kotlin null-safety 엄격성은 컴파일 단계에서 강제된다(아래 참고).

## 폴더별 지침 (CLAUDE.md) 운영

- 특정 폴더에 **그 폴더 작업 시 꼭 지켜야 할 규칙/관례**(코드만 봐선 알 수 없고, 일관되게 강제돼야 하는 것)가 있다고 판단되면, **그 폴더에 `CLAUDE.md`를 만들지 사용자에게 먼저 물어본다.** 임의로 만들지 않는다.
- 이유: 하위 폴더의 `CLAUDE.md`는 그 하위 트리의 파일을 다룰 때 자동 로드되므로, 규칙을 "가장 가까운 곳"에 두면 매번 확실히 적용되고 노이즈도 없다.
- 규칙은 가장 좁은 적용 범위의 폴더에 둔다. 상세 템플릿/레퍼런스는 같은 폴더의 다른 문서로 분리하고 `CLAUDE.md`엔 핵심 규칙+포인터만 둔다.

## 디렉터리 생성 규칙

- **디렉터리가 (다른 파일 없이) 빈 채로 남는 경우에만 `.gitkeep`을 추가한다.** (빈 디렉터리는 git이 추적하지 않으므로) 이미 파일이 있는 디렉터리에는 넣지 않는다.

## 컨벤션

- **Kotlin 소스 주석을 작성하지 않는다 (2026-08-11 강화 — 종전 "표현 불가능한 제약 허용" 폐지).** 코드는 이름과 구조로 의도를 드러내는 **self-documenting** 이 원칙이며, 신규 코드에 라인 주석·KDoc·블록 주석을 달지 않는다. 설계 제약·근거·트레이드오프는 커밋 메시지·`docs/`·ADR·지식 위키에 남긴다. (빌드 스크립트·Flyway SQL·yml 주석은 규약 밖. 기존 주석은 만나는 김에 정리하되 일괄 퍼지는 별도 작업으로.)
- 소스는 각 모듈의 `src/main/kotlin/...`, 테스트는 `src/test/kotlin/...`에서 동일 구조로 미러링한다. **패키지는 모듈 경로를 미러링한다** — `:common`은 `com.kbap.common` 아래에 커널 `core`(에러·테스트픽스처), 유틸 `util`, 도메인 `domain.<context>`, 외부 시스템 seam `port.{llm,storage,auth}`를 둔다. `:api`는 `com.kbap.api.<feature>` 기능 패키지에 controller·request/response·서비스·결과 타입을 함께 두며, 파일 수가 적은 기능에 `service`·`dto` 하위 패키지를 만들지 않는다. api 전용 공통재(BaseResponse·ApiPaths·예외핸들러·인증 부품·로깅)는 `com.kbap.api.core`, 빈 조립은 `com.kbap.api.core.config`에 둔다. 배치는 `com.kbap.batch`, 인프라는 `com.kbap.infra.<어댑터>`다. **부트 진입점 `KbapApiApplication`은 패키지 루트 `com.kbap`** 에 두어 기본 컴포넌트 스캔·AutoConfigurationPackages 가 전 계층(엔티티·리포지토리 포함)을 커버한다(별도 `scanBasePackages` 불필요). 배치 진입점은 `com.kbap.batch` — 단 배치는 `scanBasePackages` 를 자신 + `com.kbap.infra.llm` 로 좁힌다(도메인 서비스 미탑재).
- web 실행 설정은 `api/src/main/resources/`에 YAML로 둔다: 베이스 `application.yml` + 프로필별 `application-{local,dev,staging,prod}.yml`. 확장자는 `.yml`로 통일한다(`.yaml` 아님). 테스트용 오버라이드는 `api/src/test/resources/application.yml`(Flyway **on** — 운영과 동일한 마이그레이션으로 Testcontainers MySQL 스키마를 만들고 Hibernate `ddl-auto=validate` 로 엔티티↔스키마 정합을 검증). 배치는 `batch/src/main/resources/application.yml`(flyway off). 로깅은 각 앱 `logback-spring.xml`이 Boot 기본(`base.xml`)을 include 한다.
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
- **JPA 엔티티 작성 (고정).** **엔티티가 곧 도메인 모델**이다 — 도메인 메서드(`completeOnboarding`·`overallRisk` 등)를 엔티티에 두고, 별도 도메인 모델 클래스·`toDomain`/`from` 변환을 만들지 않는다. 값 객체(`MemberProfile`·`Ranking` 등)는 유지. **Spring Data Repository 는 public**(KB-220, ADR-0014) — 도메인 로직(검증·상태 전이·정책)은 **도메인 서비스**(`@Service`)가 소유하고, 단순 영속 접근은 소비 계층이 리포지토리를 직접 쓴다(위임 전용 창구 서비스 금지, 트랜잭션 경계는 사용하는 쪽이 명시 선언). 엔티티·값 객체·enum 은 `com.kbap.common.domain.<도메인>.model`에 두고 서비스·리포지토리·seam 은 해당 도메인 루트에 둔다. **모든 엔티티는 `com.kbap.common.domain.BaseEntity`(`@MappedSuperclass`)를 상속**한다 — `id`(IDENTITY)·`status`(`EntityStatus` ACTIVE/DELETED 소프트삭제)·`createdAt`·`updatedAt` 공통 제공, 엔티티엔 **자체 id·생성/수정 시각을 두지 않는다**(도메인 고유 상태는 `status` 와 컬럼명 분리 — 예: member 의 `member_status`). `kotlin-jpa`(no-arg)는 `kbap.common-conventions`(`:common`)와 `:api` build 파일이 적용한다(전 필드 기본값이 있으면 no-arg 자동 생성). JPA 애너테이션은 **use-site 타깃 없이**(`@Id`/`@Column`) 단다.
  - **컬럼 정의는 MySQL 기준으로 고정한다 (H2 호환은 고려하지 않는다).** 문자열 컬럼은 `@Column(length = N)` 으로 길이를 명시하고(예: `length = 20`), 길이 없는 `columnDefinition = "VARCHAR"` 같은 비-MySQL 형식은 쓰지 않는다. 엔티티 컬럼 길이는 Flyway 마이그레이션과 일치시킨다.
  - **소프트 삭제는 BaseEntity 가 `@SQLRestriction("status = 'ACTIVE'")` 로 상시 적용**한다(@MappedSuperclass 에서 전 엔티티로 상속). 따라서 모든 조회는 자동으로 `ACTIVE` 만 본다 — 리포지토리 쿼리에 별도 status 조건을 달지 않는다. 삭제는 row 제거가 아니라 `BaseEntity.delete()`(status=DELETED).
- **트랜잭션 경계 (고정 — 2026-07-14).** DB 를 만지는 서비스 public 메서드는 **전부 명시적 `@Transactional`**(읽기는 `readOnly = true`)을 선언한다 — 리포지토리 기본 트랜잭션에 암묵 의존 금지. 상태 변경은 관리 엔티티 dirty checking(불필요한 `save()` 호출 금지). 애플리케이션 서비스는 여러 조회를 한 스냅샷으로 묶을 때만 선언. **외부 시스템 호출은 트랜잭션 밖**(예: 탈퇴의 소셜 삭제는 `AuthApplicationService` 가 선행). 예외는 주석으로 사유 명시(예: `findOrSignUp` — unique 제약 위반 폴백이 세션을 무효화해 단일 트랜잭션 불가).
- **동시성 방어 수위 (고정 — 2026-07-30).** **트랜잭션 격리수준을 손대지 않는다** — `@Transactional` 에 `isolation` 을 지정하지 말고 DB 기본값(REPEATABLE READ)을 쓴다. 격리수준 조정으로 동시성 문제를 풀지 않는다. 동시성 방어는 **비즈니스 중요도로 판단**한다: 치명적 정합(인증·결제·중복 가입 등)만 최소 수단(원자 UPDATE·unique 제약)으로 막고, 비치명 경합(낮은 확률의 카운트 오차 등)은 감수한다. **과한 동시성 시나리오를 매번 테스트로 작성하지 않는다** — 스레드 동시 실행 테스트는 명시적으로 요구된 치명 경로에만 둔다.
- **Flyway 마이그레이션 버전 규칙 (고정).** 마이그레이션 버전은 **점 구분 timestamp** `Vyyyy.MM.dd.HH.mm.ss__description.sql` 로 짓는다 — 값은 **파일 생성 시점의 로컬 현재 시각**(각 파트 두 자리 zero-pad), 예: `V2026.07.05.14.30.12__add_review_table.sql`. 병렬 브랜치에서 각자 다음 정수를 잡을 때 생기는 버전 번호 머지 충돌을 없애기 위함이다(Flyway 공식 유효 포맷 — 예시 `2013.01.15.11.35.56`). Flyway 는 버전을 숫자 파트열로 정렬한다. 기존 정수 마이그레이션(`V1`~`V10`)은 **로컬 DB 전용·프로덕션 이전 단계에서 커밋 시각 기준 timestamp 로 일괄 전환**했으므로(KB-44) 현재 모든 마이그레이션이 timestamp 포맷이다. 생성 시각 기반이라 먼저 만들고 늦게 머지된 과거 버전이 out-of-order 로 적용될 수 있어 **`spring.flyway.out-of-order=true`**(베이스 `application.yml`)를 켜 두며, 그 전제로 **각 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않게 독립적으로 작성**한다. **금지 사례**: (1) 신규에 정수 버전(`V11`) 사용, (2) **공유/프로덕션 DB 에 이미 적용된** 마이그레이션 파일 수정·리네임(checksum/history 파손 — 일괄 전환은 로컬 전용·프로덕션 이전 단계에서만 가능), (3) 순서 의존 마이그레이션 작성. (상세·근거: [`docs/architecture/meogo-conventions.md`](docs/architecture/meogo-conventions.md) "Flyway 마이그레이션 버전 규칙".)
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
- **에러 코드 체계 (고정)**: `ErrorCode` enum(`:common` — `common.core.error`) 단일 출처 — `code` 는 **도메인 접두 + 3자리 채번**(`COMMON-002`·`AUTH-004`·`MEMBER-003`·`FOOD-001`). 폐기된 번호는 재사용하지 않는다(`COMMON-001` = 구 `UNSUPPORTED_LANGUAGE`, KB-201 에서 삭제). `KB-` 접두는 Jira 이슈 키와 충돌하므로 금지. 클라이언트는 `code` 로만 분기하고 `message` 매칭은 금지(문구는 자유 변경). 예: access 만료 `AUTH-004` → refresh 호출, refresh 만료 `AUTH-006` → 재로그인. 형식·유일성은 `ErrorCodeStatusTest` 가 강제. 예외는 `BusinessException(errorCode, payload = null)` 하나 — 도메인별 예외 클래스를 만들지 않는다.
- 컨트롤러는 raw 도메인/DTO 를 직접 반환하지 않고 항상 `ResponseEntity<BaseResponse<T>>`로 감싼다. HTTP 상태코드는 `ResponseEntity`로, 비즈니스 성공/실패 플래그는 `BaseResponse.success`로 표현한다.
- `BaseResponse`는 모든 web 응답이 공유하므로 `:api` 에 둔다. 페이로드 타입 `T`는 각 API 의 응답 DTO 다.

### API 엔드포인트 경로 규약 (고정)

**모든 컨트롤러 경로는 `/api` 로 시작하고, 버저닝은 URL 이 아니라 `X-API-Version` 헤더가 담당한다**(2026-08-11, KB-321/#144 — Spring 네이티브 API 버저닝 도입. 2026-08-13, KB-331 — URI 버전 세그먼트 전면 제거 + 헤더 필수화).

- 경로 베이스는 `com.kbap.api.core.ApiPaths` 의 상수로 **단일 출처** 관리한다(`const val API = "/api"`). 경로 문자열에 `/api` 를 직접 하드코딩하지 않는다.
- **모든 리소스는 `ApiPaths.API + "/<리소스>"`**(예: `/api/scans`·`/api/reviews`)에 둔다. **`X-API-Version` 헤더는 필수**다 — 기본값이 없고, `/api/**` 요청에서 누락·미지원 버전이면 400(COMMON-002)이다. **유일 예외는 `GET /api/app-version`**(강제 업데이트 안내의 복구 경로 — 헤더 없이 동작). 예외 범위의 단일 출처는 `WebConfig` 의 폴백 버전 리졸버 하나다(비-`/api` 경로·app-version 에 1.0 공급) — 필터·컨트롤러에 예외 로직을 흩뿌리지 않는다.
- **기존 엔드포인트의 새 버전은 경로를 바꾸지 않고 같은 컨트롤러에서 `version` 만 올린다** — `@PatchMapping("/me/profile")`(기본) 옆에 `@PatchMapping("/me/profile", version = "1.1+")` 를 두는 식이다(`MemberController` 의 프로필 수정·온보딩 `1.0`/`1.1+`). **`*V2Controller`·`*V2Api` 같은 버전별 클래스를 만들지 않는다** — 클라이언트는 URL 을 그대로 두고 헤더만 올린다. 버전 조건이 있는 매핑이 없는 매핑보다 우선하므로 기본 버전 핸들러는 `version` 없이 둔다.
- **버전 번호는 앱 릴리스 마커다** — 엔드포인트마다 따로 세지 않는다. 같은 릴리스에서 바뀐 엔드포인트들은 같은 번호를 쓴다(온보딩 자동 지정과 프로필 국적 잠금이 둘 다 `1.1+`). 그래서 **클래스·DTO 이름에 버전 번호를 박지 않는다** — 번호는 옮겨 다니고 이름은 남아 거짓이 된다. 계약의 차이로 이름 짓는다(`ProfileUpdateNoCountryRequest`).
- **URI 버전 세그먼트는 존재하지 않는다** — 레거시 `/api/v1` 베이스와 `ApiPaths.V1` 상수는 KB-331 에서 제거됐다(`V2` 상수는 KB-322 에서 제거). `/api/v1`·`/api/v2` 류 경로를 다시 만들지 않는다. 같은 경로에 무버전 매핑(구 계약)과 버전 매핑(신 계약)이 공존할 수 있다(예: `/api/scans` 의 v1 스캔(무버전)과 2.0 스캔) — springdoc 전체 문서에서는 path+method 충돌 시 한 오퍼레이션만 실리므로 버전별 계약은 그룹 문서(`/v3/api-docs/<version>`)로 본다.
- 이 규약은 **비즈니스 API(`com.kbap.api` 컨트롤러)** 에만 적용한다. actuator·springdoc(Swagger UI) 등 프레임워크 경로는 규약 밖이며 자체 경로를 유지한다.
- **새 경로는 `WebConfig` 의 JWT 보호 경로(`addUrlPatterns`)에 반드시 등록한다** — 누락하면 그 엔드포인트의 전 시나리오가 401 로 실패한다(실제로 두 번 밟은 함정).

### 파라미터 애너테이션 위치 (고정)

**Spring 애너테이션은 전부 구현 컨트롤러 클래스에, swagger 문서 애너테이션만 `*Api` 인터페이스에 둔다.** 컨트롤러: 매핑(`@GetMapping`·`@PostMapping` 등), web 바인딩(`@RequestBody`·`@PathVariable`·`@RequestParam`·`@Valid`), 인증 리졸버(`@AuthMemberId`·`@AuthMemberIdOrNull`). 인터페이스: swagger 문서(`@Tag`·`@Operation`·`@Parameter`·`@ApiResponses`·`@SecurityRequirement`·`@io.swagger...RequestBody`)만. Spring 은 인터페이스 선언도 병합해 해석하지만(HandlerMethodParameter), 개발자가 컨트롤러 파일만 열어 그 엔드포인트의 경로·바인딩·인증 방식을 즉시 파악할 수 있어야 한다 — 인터페이스에만 두면 컨트롤러에선 평범한 파라미터로 보여 오독한다. 인터페이스 쪽 파라미터는 애너테이션 없이 타입만 맞춘다(중복 선언 금지 — 두 곳이 어긋나면 어느 쪽이 진실인지 모호해진다). swagger 문서 노출은 `OpenApiConfig` 의 `SpringDocUtils.addAnnotationsToIgnore` 가 인증 애너테이션 두 개를 숨기므로 `@Parameter(hidden = true)` 를 따로 달지 않는다.

<!-- SPECKIT START -->
현재 브랜치의 구현 플랜은 `.specify/feature.json`(git 비추적 — speckit 커맨드가 브랜치마다 재생성)이 가리키는 `specs/<feature>/plan.md` 를 읽는다. 과거 기능의 설계 맥락·선례가 필요하면 `specs/` 디렉터리에서 해당 기능(디렉터리명 = `kb-<nn>-slug`)의 plan.md 를 직접 읽는다.

이 블록은 **고정 문구**다 — 브랜치별 플랜 포인터·요약을 여기에 쓰지 않는다. 모든 feature 브랜치가 같은 줄을 교체해 머지마다 충돌이 났던 구조를 제거한 것(2026-07-20). `/speckit-plan` 은 CLAUDE.md 를 수정하지 않는다.
<!-- SPECKIT END -->
