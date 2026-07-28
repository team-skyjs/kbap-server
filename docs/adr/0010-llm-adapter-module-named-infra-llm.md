# ADR-0010: LLM 호출 어댑터 전용 모듈 `:infra:llm` 신설 — 배치가 직접 의존

- **상태**: Accepted (2026-07-06)
- **관련**: [specs/kb-49-llm-client-foundation](../../specs/kb-49-llm-client-foundation/plan.md) · Jira KB-49 · [ADR-0008](./0008-modular-monolith-shared-domain.md)(모듈러 모놀리스·batch 직접 의존). ADR-0008 의 "(external/ LLM 등 외부 — 추후 LLM 착수 시 추가)" 예고를 구체화한다.

## Context

배치(수집·스코어링)가 쓸 **LLM 호출 토대**가 필요하다. OpenAI·Upstage·Gemini 3개 모델을 병렬 fan-out 하고 개별 모델 실패를 격리해 성공분만 모으는 클라이언트, 그리고 요청/응답 값타입·Spring AI 구성이 한 벌 필요하다.

이 코드를 어디에 둘지에 두 축의 결정이 걸려 있었다.

- **모듈 위치·이름.** ADR-0008 은 외부 어댑터 자리로 `:infra:external`(범용 외부 연동 catch-all — LLM·email·MQ 등)을 예고만 해 두었다. LLM 착수 시점에 이 범용 모듈을 실제로 만들지, 아니면 LLM 전용 모듈을 둘지 정해야 한다.
- **소비 경로.** 클린아키텍처 원칙(ADR-0008 원칙 III)상 `:application:*` 유스케이스는 인프라 구현체에 직접 의존하지 않고 `:core:kernel` 의 port 인터페이스로만 외부를 사용하며, 부트앱이 구현체를 `runtimeOnly` 로 조립한다. 그러나 이번 LLM 의 **소비자는 배치 잡 하나뿐**이고, 배치는 부트앱(top layer)이라 인프라 어댑터를 직접 조립·호출하는 것이 허용된다.

제약: 키가 없어도 `:batch`·`:api` 부팅이 회귀 없이 떠야 한다(Spring AI 스타터 자동구성이 키 없이 떠서 부팅을 깨면 안 됨). 공개 API 는 벤더 중립이어야 한다(Spring AI 타입을 어댑터 밖으로 노출하지 않음). fan-out 부분 실패는 실 네트워크 없이 단위 검증 가능해야 한다(헌법 I).

## Decision

**LLM 호출 어댑터를 전용 모듈 `:infra:llm` 하나로 신설한다.** 예고했던 범용 `:infra:external` 은 만들지 않는다(초안 명칭 `:infra:client` 도 폐기).

- 공개 API `LlmFanoutClient`(`generate(LlmChatRequest) → LlmFanoutResult`)·값타입(`LlmChatRequest`·`LlmChatResult`·`LlmModelFailure`·`LlmFanoutResult`·`LlmModelId`)·단일모델 seam(`LlmModelCaller`)·Spring AI 구성(`LlmConfiguration`·`LlmModelProperties`·`SpringAiModelCaller`)을 **모두 이 모듈에 응집**한다. Spring AI `ChatModel` 의존은 `:infra:llm` 내부에만 갇힌다.
- **`:batch` 가 `:infra:llm` 를 `implementation` 으로 직접 의존**해 잡에서 `LlmFanoutClient` 를 주입받아 호출한다. **`:core:kernel` port·`runtimeOnly` 조립은 생략**한다 — 단일 소비자(배치)라 간접층의 비용만 크고 이득이 없다(속도 우선).
- OpenAI·Upstage·Gemini 3개 `ChatModel` 을 `meogo.llm.*` 프로퍼티 + `@ConditionalOnProperty(prefix = "meogo.llm.<model>", name = ["enabled"], havingValue = "true")` 로 **명시 구성**한다. Upstage 는 OpenAI 호환이라 openai 스타터를 base-url 만 교체해 재사용하고, Gemini 는 google-genai 스타터(API 키 방식)를 쓴다. **키/활성 플래그가 없으면 caller 빈이 아예 생성되지 않아** 부팅이 안전하며, Spring AI 자동구성 유입은 `spring.ai.model.*=none` 으로 차단한다.
- fan-out 은 **JDK 21 가상 스레드**(`Executors.newVirtualThreadPerTaskExecutor()`) + `CompletableFuture` 로 구현한다(신규 의존 없음). 단일모델 호출을 `LlmModelCaller` seam 뒤에 두어 페이크로 부분 실패·전멸·병렬성을 단위 검증한다(헌법 I). 실키 3모델 스모크는 평소 비활성(`-Dllm.smoke.enabled=true` 게이트)로 두고 수동 실행한다.

## Alternatives Considered

- **예고대로 범용 `:infra:external`(email·MQ 등 catch-all) 모듈에 LLM 을 넣기.** 아직 LLM 외의 외부 연동이 없어 범용 모듈은 빈 추상화다. 서로 무관한 외부 기술(LLM·메시징·메일)이 한 모듈에 섞이면 의존성·부팅 자동구성·테스트가 얽힌다. 전용 `:infra:llm` 이 응집도가 높고, 다른 외부 연동이 실제로 생기면 그때 각자의 전용 모듈(또는 필요 시 범용 모듈)을 판단한다. (범용 `:infra:external` catch-all 서술 자체는 폐기가 아니라 이번 범위 밖으로 남겨 둔다.)
- **처음부터 `:core:kernel` 에 LLM port 를 두고 `runtimeOnly` 로 조립.** 클린아키텍처 정석이지만 현재 소비자가 배치 잡 하나뿐이라 port 인터페이스 + 조립 배선이라는 간접층이 순수 오버헤드다(YAGNI). web/`:application:*` 이 LLM 을 재사용해야 하는 시점 — 즉 유스케이스 계층이 LLM 을 조율해 원칙 III("application → infra 금지") 경계가 실제로 필요해지는 시점 — 에 kernel port 로 **승격**하기로 하고, 지금은 배치가 부트앱에서 직접 호출한다.
- **모델별 스레드 풀·직접 `Thread` 관리 / 스타터 상시 자동구성.** 가상 스레드 + `CompletableFuture` 로 충분하고 풀 튜닝 부담이 없다. 스타터 상시 자동구성은 키 없는 환경에서 부팅을 깰 위험이 있어 `@ConditionalOnProperty` + `spring.ai.model.*=none` 명시 구성을 택했다.

## Consequences

**+**
- LLM 관련 코드(공개 API·값타입·구성·fan-out)가 한 모듈에 응집 — 벤더 타입이 어댑터 밖으로 새지 않는다.
- 키 없이 배치/웹 부팅 무회귀(빈 미생성 + `spring.ai.model.*=none`).
- 단일모델 seam 으로 실 네트워크 없이 부분 실패·병렬성을 단위 검증(헌법 I). 모델 추가 = enum 상수 1개 + 빈 1개로 N 일반화.
- 배치가 간접층 없이 바로 호출 — 배선이 단순하고 빠르다.

**−**
- `:batch` 가 인프라 구현 모듈(`:infra:llm`)을 컴파일 의존한다(port 뒤에 숨지 않음). 부트앱→인프라 직접 의존이라 원칙 III 위반은 아니지만, **web/application 재사용 시 `:core:kernel` port 승격 + `runtimeOnly` 조립으로 리팩터**해야 한다(트리거: `:application:*` 유스케이스가 LLM 을 조율하게 될 때).
- 실키 3모델 스모크는 CI 에서 자동 검증되지 않는다(비용·키 문제) — 게이트된 수동 절차(quickstart §3)로 남는다.

## 후속

- web/`:application:*` 이 LLM 을 재사용하게 되면 `:core:kernel` 에 LLM port 를 도입하고 `:infra:llm` 이 이를 구현, 부트앱이 `runtimeOnly` 로 조립하도록 승격한다.
- 다른 외부 연동(email·MQ 등)이 실제로 필요해지면 그 시점에 전용 모듈 여부를 판단한다(범용 `:infra:external` 서술은 그때까지 참고로 유지).
