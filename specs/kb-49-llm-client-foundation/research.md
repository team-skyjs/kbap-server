# Phase 0 Research: LLM 호출 토대

문서 규약: 각 항목은 **Decision / Rationale / Alternatives considered** 로 기록한다. 이 기능은 기술 미지수가 대부분 "구성 방식" 결정이라, NEEDS CLARIFICATION 마커 대신 근거 있는 기본값으로 확정하고 잔여 검증 항목만 §11 에 남긴다.

## D1. 모듈 명칭 — LLM 전용 `:infra:llm` (초안 `:infra:client` 재확정, 2026-07-06 clarify)

- **Decision**: LLM 어댑터 모듈을 **`:infra:llm`** 로 신설하고 그 안에서 Spring AI `ChatModel` 을 의존해 호출한다. ADR-0008·spec 005 가 예고한 `:infra:external` 및 초안의 `:infra:client` 를 대체하며, 명명 근거를 **ADR-0010** 으로 남긴다.
- **Rationale**: LLM 호출은 raw HTTP 가 아니라 **Spring AI 인터페이스**로 이뤄지므로, 범용 "client" 보다 도메인 의미가 분명한 **LLM 전용 이름**이 낫다(사용자 재확정). ADR-0008 이 상정한 범용 external catch-all(email·MQ·storage·알림)은 지금 다루지 않고(범위 밖), **현재 구현하는 LLM 모듈에만 집중**한다.
- **Alternatives considered**: `:infra:client`(초안 — 범용 외부 client 의미이나 Spring AI 로 LLM 만 호출하는 현 범위엔 과범용이라 재확정으로 배제), `:infra:external`(원안 catch-all — LLM 전용화로 부적합), per-concern 전면 전환(email·MQ 까지 지금 모듈화 — 범위 초과라 보류).

## D2. 계약·값타입 위치 — `:infra:llm` (kernel port 생략, 2026-07-06 clarify)

- **Decision**: fan-out 공개 API `LlmFanoutClient` 와 요청/응답/실패 값타입을 `com.meogo.infra.llm` 에 둔다. `:core:kernel` LLM port 는 두지 않고, `:app:batch` 가 `:infra:llm` 를 직접 의존해 호출한다.
- **Rationale**: 단일 소비자(배치)라 계약을 kernel 로 올려 간접층을 만들 이득이 낮다. 부트앱(`:app:batch`)이 인프라 어댑터를 직접 조립·호출하는 것은 헌법 III 상 허용(app 은 top layer). 공개 API 는 벤더 중립(Spring AI 타입 미노출)이라 소비자는 벤더 SDK 를 모른다.
- **트리거(후속)**: `:application:batch` 유스케이스가 LLM 을 조율하거나 web 이 재사용해야 하면, 그때 `:core:kernel` 로 port 를 승격한다(application→infra 금지 원칙 III 준수). port 는 순수 인터페이스라 추출 비용 저렴.
- **Alternatives considered**: kernel port(초안 — 다중 소비자·벤더 중립엔 이상적이나 단일 소비자 현 범위엔 과함), `:application` 배치(교차 진입점 공유 이슈), 배치에 완전 인라인(모듈 재사용·테스트 seam 약화 — `:infra:llm` 모듈 유지로 배제).

## D3. 병렬 fan-out 오케스트레이션 — `:infra:llm` 의 `LlmFanoutClient`, 단일모델 seam 분리

- **Decision**: 병렬 호출/부분실패 격리는 `:infra:llm` 의 공개 클래스 `LlmFanoutClient` 가 수행한다. 각 모델 호출은 seam 인터페이스 `LlmModelCaller`(단일 모델 1회 호출) 뒤에 두고, `LlmFanoutClient` 는 `List<LlmModelCaller>` 를 주입받아 반복한다.
- **Rationale**: 단일모델 seam 을 인터페이스로 분리하면 **실 네트워크 없이 페이크 여러 개로** 병렬성·부분실패를 단위 테스트할 수 있다(US1 독립 테스트, FR-010, 헌법 I).
- **Alternatives considered**: ChatModel 을 `LlmFanoutClient` 가 직접 리스트로 들기(Spring AI 타입에 테스트가 결합 — 페이크 곤란).

## D4. 동시성 메커니즘 — JDK 21 가상 스레드 + CompletableFuture

- **Decision**: `Executors.newVirtualThreadPerTaskExecutor()` 빈에 각 `LlmModelCaller.call` 을 `CompletableFuture.supplyAsync` 로 태워 병렬 실행하고, future 별 `try/catch`(또는 `handle`)로 성공↔실패를 분할한다. 신규 라이브러리 의존 없음.
- **Rationale**: LLM 호출은 블로킹 IO fan-out 이라 가상 스레드가 이상적(스레드당 블로킹 부담 무시 가능, 풀 크기 튜닝 불요). JDK 21 toolchain 이미 사용. 총 소요가 가장 느린 단일 호출에 수렴(SC-001). 개별 future 격리로 부분실패 자연 표현(FR-005).
- **Alternatives considered**: `kotlinx.coroutines`(신규 의존 추가 — 현재 미사용, 이득 대비 과함), Reactor/`ChatModel` 리액티브 스트리밍(스트리밍 불요·복잡), `parallelStream`(공용 ForkJoinPool 을 블로킹 IO 로 점유 — 부적절), 순차 호출(SC-001 병렬성 미달), JDK21 `StructuredTaskScope`(개념적으로 fan-out 에 가장 적합하나 JDK21 에서 **preview** — `--enable-preview` + API 변동 리스크라 운영 코드에서 배제).
- **독립 리뷰(Codex/gpt-5.5, 적대적 검토)**: 이 조건("코루틴 미도입 + non-suspend blocking port + 소규모 blocking fan-out")에서 **가상스레드+CompletableFuture 를 최적으로 확정**. 코루틴은 결국 `runBlocking { supervisorScope { async(Dispatchers.IO){ blockingCall() } } }` 로 suspend 세계만 새로 만들 뿐 내부는 여전히 블로킹 스레드에서 돌아 **본질적 우위 없음** — 신규 의존의 개념 비용만 증가. 코루틴이 우월해지는 반례(현재 미해당): suspend-native LLM SDK, fan-out 후 Flow/suspend 파이프라인, web 을 coroutine controller 로 전환, 취소 전파가 핵심 요구, 수백~수천 규모 fan-out. → **결정 유지**. 리뷰가 지목한 구현 가드레일은 §D4a 로 반영.

### D4a. 가상스레드 fan-out 구현 가드레일 (Codex 리뷰 반영 — tasks/구현 시 강제)

- **G1 (실 취소는 HTTP 타임아웃)**: `CompletableFuture.orTimeout`/`cancel` 은 underlying 블로킹 HTTP 를 끊지 못한다(보조 안전장치일 뿐). **provider별 connect/read 타임아웃을 ChatModel/HTTP client 에 필수 설정**하고, future 타임아웃은 그 위 보조로만 둔다.
- **G2 (예외 전파 금지)**: `allOf(...).join()` 의 예외 전파에 기대지 않는다. **future별 `handle`/`exceptionally` 로 성공→`LlmChatResult`·실패→`LlmModelFailure` 를 값으로 접어** 부분 실패를 표현(FR-005).
- **G3 (전멸 계약)**: 전부 실패/전비활성은 예외가 아니라 빈 성공집합 반환을 코드로 명시(D8 재확인).
- **G4 (executor 생명주기)**: 가상스레드 executor 를 **빈으로 공유하면 앱 shutdown 시 close**(`DisposableBean`/`@Bean(destroyMethod)`), 호출당 생성하면 `.use { }` 로 반드시 close. 이 규모에선 둘 다 허용.
- **G5 (MDC/ThreadLocal)**: 가상스레드는 새 스레드라 MDC/SecurityContext 가 자동 전파되지 않는다. **batch 중심이라 현 위험 낮음** — 다만 web 에서 correlation 로그가 필요해지면 capture/restore 를 명시 전파(현재는 불요, 과설계 금지).
- **G6 (범위 밖·후속 의제)**: provider 증설/웹 동시호출 증가 시의 **rate limit·retry·circuit breaker·bulkhead** 는 fan-out 구현 문제가 아니라 외부 API 운영 의제 — 이 토대에서는 만들지 않는다(과설계 방지).

## D5. 빈 구성·부팅 안전 — 수동 빈 + `@ConditionalOnProperty`, `spring.ai.model.*=none` 유지

- **Decision**: Spring AI 자동구성은 `spring.ai.model.*=none`(기존 유지)으로 끈 채, 3개 `ChatModel`(또는 곧장 `LlmModelCaller`) 빈을 **`LlmConfiguration` 에서 명시 생성**하고 각 빈에 `@ConditionalOnProperty("meogo.llm.<model>.enabled", havingValue = "true")` 를 건다. 활성 플래그/키가 없으면 어떤 모델 빈도 만들지 않는다. `LlmFanoutClient` 는 주입된 `List<LlmModelCaller>`(0개 가능)로 동작한다.
- **Rationale**: 자동구성(none)도 안 뜨고 우리 빈도 조건부라, 키 없는 환경(로컬/CI/기존 web·batch)에서 컨텍스트 로딩이 실패하지 않는다(FR-008·SC-003). 빈 리스트여도 fan-out 은 빈 성공집합 반환(D8).
- **Alternatives considered**: Spring AI 자동구성 활성화 후 키만 비움(스타터별 실패 모드 예측 어렵고 부팅 리스크), `@Profile` 로만 제어(키 유무와 프로필이 어긋나면 빈 생성 실패 → 조건부 프로퍼티가 더 견고).

## D6. 설정 프로퍼티 스키마 — `meogo.llm.{openai,upstage,gemini}`

- **Decision**: `@ConfigurationProperties("meogo.llm")` 로 모델별 `enabled`·`apiKey`·`baseUrl`·`model`(모델명) 을 바인딩한다. Upstage 는 OpenAI 호환이라 OpenAI 클래스(`OpenAiApi`/`OpenAiChatModel`)를 **`baseUrl` 만 Upstage 엔드포인트로 교체**해 재사용한다. Gemini 는 google-genai 스타터의 ChatModel 을 사용. 키/엔드포인트는 루트 `.env`(기존 `spring.config.import`) 또는 OS 환경변수로 주입.
- **Rationale**: 벤더별 대칭 구조로 프로필/환경별 on-off 와 키 회전을 단순화. Spring AI 표준 `spring.ai.openai.*` 대신 자체 네임스페이스를 쓰는 이유 — 3벤더(특히 OpenAI 스타터를 Upstage 로 2회 인스턴스화)를 **명시 빈**으로 통제하려면 자동구성 프로퍼티가 아니라 우리 프로퍼티가 필요.
- **Alternatives considered**: Spring AI 표준 프로퍼티(`spring.ai.openai.api-key` 등) 사용(자동구성 재활성 필요 → D5 부팅안전과 충돌, Upstage 2번째 인스턴스 표현 곤란).

## D7. 부트앱 조립 — `:app:batch` 가 `:infra:llm` 를 `implementation` 직접 의존 (2026-07-06 clarify)

- **Decision**: `:app:batch` 가 `:infra:llm` 를 **`implementation`** 으로 의존해 잡에서 `LlmFanoutClient` 를 직접 주입·호출한다. `app/batch/build.gradle.kts` 의 낡은 "디커플드·내부 모듈 무의존" 주석을 ADR-0008 기준으로 정정하고, `app/batch/.../application.yml` 에 `spring.ai.model.*=none` 을 추가한다. **`:app:api` 는 이번 범위에서 LLM 미의존**(web 재사용은 후속).
- **Rationale**: 배치가 유일 소비자라 runtimeOnly 조립(port 구현 런타임 주입)의 간접층이 불필요 — 직접 의존이 단순·빠름. batch 는 이미 부트앱에서 도메인 port 를 직접 쓰는 패턴이라 일관.
- **Alternatives considered**: 두 부트앱 runtimeOnly 조립(kernel port 전제 — 이번 범위 미채택), batch 완전 인라인(`:infra:llm` 모듈로 재사용성·seam 확보 위해 배제).
- **Risk/Follow-up**: spring-ai 스타터가 배치(`spring-boot-starter`, 비-web)에 spring-web/servlet 컨테이너를 끌어오는지 확인 → 필요 시 전이 배제. Spring AI 는 `RestClient` 기반이라 서블릿 컨테이너 없이 동작 예상(§11-V1).

## D8. 전멸/전비활성 시 의미 — 예외 대신 빈 성공집합

- **Decision**: 활성 모델 0개이거나 전부 실패해도 `LlmFanoutClient.generate` 는 예외를 던지지 않고 `successes=[]` + `failures=[...]`(전비활성이면 둘 다 빈) 를 반환한다.
- **Rationale**: "부분 실패 허용" 계약의 일관된 확장. 전체 실패 판단·재시도·에스컬레이션은 호출자(후속 배치)의 정책 영역이라 토대는 사실만 전달한다.
- **Alternatives considered**: 전멸 시 예외(호출자가 부분/전체를 분기 못 하고 try/catch 강제 — 계약 비일관), 최소 1개 성공 보장(외부 가용성에 의존 — 보장 불가).

## D9. 실키 스모크 검증 방식 — `@Disabled` 통합 테스트 + 문서 절차

- **Decision**: 3모델 각 1회 실호출 스모크는 `@Disabled`(또는 태그) 통합 테스트로 코드화하고, `quickstart.md` 에 로컬에서 키를 넣고 활성화해 실행하는 **수동 절차**를 문서화한다(FR-012).
- **Rationale**: 실 API 는 유료·비결정·키 필요라 CI 상시 실행 부적합. 코드로 남겨 재현성 확보 + 수동 트리거로 실검증.
- **Alternatives considered**: CI 상시 스모크(비용·플레이키·시크릿 노출), 문서만(재현 코드 부재).

## D10. 문서 정합 범위 — 현재 구현 직결분만(외부 catch-all 서술 보존, 2026-07-06 clarify)

- **Decision**: LLM 맥락의 참조만 `:infra:llm` 로 갱신한다 — `settings.gradle.kts`(placeholder 주석 → `include(":infra:llm")`), `app/batch/build.gradle.kts`(`implementation(project(":infra:llm"))` + 낡은 "디커플드" 주석 정정), `CLAUDE.md` 의 LLM 기술스택·모듈 라인. ADR-0008·`docs/architecture/*`(meogo-conventions·module-structure·research·use-case-flows)·`gradle/libs.versions.toml` 의 **범용 `:infra:external` catch-all 서술(email·MQ·storage·알림)은 이 기능 범위 밖으로 두고 변경하지 않는다.** ADR-0010 이 결정의 단일 근거.
- **Rationale**: 사용자 지시 "지금은 현재 구현(LLM)에만 집중". `:infra:llm` 은 LLM 전용이라 email·MQ 서술을 `:infra:llm` 로 바꾸면 의미가 틀어진다. 범용 external 의 운명(per-concern 분리 vs catch-all 유지)은 별도 후속 의사결정으로 미룬다.
- **Alternatives considered**: 전 문서 일괄 리네임(email·MQ 를 llm 으로 만들어 의미 오류), external 개념 전면 폐기(범위 초과·큰 문서 개정).

## §11. 잔여 검증 항목(구현 시 확인 — 저위험·기계적)

- **V1 (배치 web 전이)**: `spring-ai-starter-model-*` 를 `:app:batch` 클래스패스에 올렸을 때 서블릿 컨테이너/`spring-boot-starter-web` 가 딸려와 배치 앱 성격이 바뀌는지. → 부팅 후 `web-application-type` 확인, 필요 시 전이 배제. (D7 리스크)
- **V2 (Spring AI 2.0 수동 빈 시그니처)**: `OpenAiApi`/`OpenAiChatModel` 빌더와 `google-genai` ChatModel 의 정확한 2.0.0 생성 API. autoconfig(`=none`) 비활성 상태에서 수동 생성 경로 확인. → 구현 시 Spring AI 2.0.0 javadoc/샘플 대조(기계적).
- **V3 (Gemini 인증 방식)**: google-genai 스타터가 Gemini Developer API **API 키** 방식인지(예상) vs Vertex GCP 자격증명 방식인지. → `apiKey` 프로퍼티로 충분한지 확인, Vertex 계열이면 프로퍼티 스키마(project-id·location) 보강.
- **V4 (프로퍼티 relaxed binding)**: `meogo.llm.openai.api-key` ↔ `apiKey` 카멜/케밥 바인딩 정상 확인(표준 동작, 테스트로 고정 — D6).
