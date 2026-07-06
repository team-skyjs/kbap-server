# Feature Specification: LLM 호출 토대 — `:infra:llm` 모듈(Spring AI 3모델 병렬 fan-out), 배치가 직접 의존

**Feature Branch**: `kb-49-llm-client-foundation`

**Created**: 2026-07-06

**Status**: Draft

**Jira**: KB-49 — [BE] LLM 토대: Spring AI 3-모델 구성 + 병렬호출 클라이언트

**Input**: User description: "jira kb-49 배치 모듈 내에서 음식에 대한 기피 성분 수집 배치를 구현하려고 함. 태스크에 적힌 내용과 다르게 infra:external 말고 외부 API 호출을 의미하는 다른 별칭으로 수정해서 진행하려고 함. 현재 다른 세션에서 작업을 진행중이라 워크트리로 진행되어야 함"

> **범위 확정(사용자 승인).** 대상은 **KB-49 — LLM 호출 토대**다: **`:infra:llm`** 모듈 신설(Spring AI `ChatModel` 내장) + 3개 모델(OpenAI·Upstage·Gemini) 병렬 fan-out(부분 실패 격리) + 배치가 이 모듈을 소비. **음식 기피 성분 수집 배치 잡 구현 자체는 비범위**(이 토대를 소비하는 후속 배치 태스크).
>
> **구조 확정(사용자 승인 — 2026-07-06 clarify).** ① 모듈명은 **`:infra:llm`**(LLM 을 Spring AI `ChatModel` 인터페이스로 호출하므로 LLM 전용 이름 — 초안 `:infra:client`·예고 `:infra:external` 대체). ② **`:app:batch` 가 `:infra:llm` 를 `implementation` 으로 직접 의존**해 잡에서 호출한다 — **`:core:kernel` LLM port 와 runtimeOnly 조립은 생략**(단일 소비자=배치, 속도 우선). ③ 배치는 **단일 `:app:batch` bootJar** 로 두고 잡을 user/admin 패키지로 구분한다. ④ ADR-0008 이 상정한 범용 `:infra:external` catch-all(email·MQ 등)은 **범위 밖**.

## Clarifications

### Session 2026-07-06

- Q: LLM 을 Spring AI `ChatModel` 인터페이스로 호출한다면 모듈명을 `:infra:client` 로 둘 필요가 있나 → A: 아니다. **LLM 전용 모듈 `:infra:llm`** 로 신설하고 그 안에서 Spring AI 를 의존해 호출한다.
- Q: `:infra:llm`(LLM 전용)으로 바꾸면 원래 `:infra:external` 이 담기로 한 비-LLM 외부연동(email·MQ·storage) 개념은 → A: 지금은 현재 구현(LLM)에만 집중한다 — 범용 external catch-all 개념·문서는 범위 밖.
- Q: LLM 계약(port)을 `:core:kernel` 에 둘지 → A: 두지 않는다. **`:app:batch` 가 `:infra:llm` 를 직접 의존**(implementation)해 잡에서 호출한다(kernel port·runtimeOnly 조립 생략, 속도 우선). web/application 이 재사용해야 할 때 kernel port 로 승격은 후속.
- Q: 사용자용 배치와 관리자용 배치를 별도 모듈로 나눌지 → A: 지금은 **단일 `:app:batch` bootJar** 에 담고 잡을 user/admin 패키지로 구분한다. 별도 bootJar 분리는 구체적 운영 드라이버(배포·스케일·보안·자원/장애 격리) 발생 시 후속.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 배치가 하나의 클라이언트로 N개 모델 병렬 호출 + 부분 실패 격리 (Priority: P1)

배치 잡 개발자는 `:infra:llm` 의 fan-out 클라이언트에 요청 하나를 넘겨, 활성화된 N개 LLM 모델을 **병렬로 호출**하고 성공한 모델의 결과만 모아 받고 싶다. 한 모델이 느리거나 실패해도 나머지 결과 수집은 막히지 않는다.

**Why this priority**: 이 토대의 존재 이유. 수집 배치·스코어링 배치가 공통으로 재사용할 "여러 모델을 병렬 호출해 부분 실패를 견디는" 능력이 KB-49 DoD의 핵심이다. 이 슬라이스만 있어도(페이크 모델로) 다중 모델 오케스트레이션을 단독 검증할 수 있다.

**Independent Test**: `:infra:llm` 의 단일모델 seam(`LlmModelCaller`)을 페이크 여러 개로 구현해, fan-out 클라이언트 한 번 호출이 (a) 모든 모델을 병렬 실행하고 (b) 한 모델이 예외/타임아웃을 던져도 나머지 성공 결과를 온전히 반환하는지 단위 테스트로 확인한다(실 API 키·네트워크 불필요).

**Acceptance Scenarios**:

1. **Given** 활성화된 모델 3개(모두 정상), **When** 하나의 프롬프트로 fan-out 클라이언트를 호출하면, **Then** 3개 모델의 결과가 모두 담긴 결과 집합을 반환한다.
2. **Given** 활성화된 모델 3개 중 1개가 예외를 던짐, **When** 호출하면, **Then** 성공한 2개 결과를 반환하고 실패한 1개는 식별 가능한 형태로 분리한다(전체 호출은 중단되지 않는다).
3. **Given** 활성화된 모델 3개, **When** 호출하면, **Then** 병렬 수행되어 총 소요가 세 호출의 합이 아니라 가장 느린 단일 호출에 수렴한다.
4. **Given** 활성화된 모델이 0개(전부 비활성/키 없음), **When** 호출하면, **Then** 예외를 던지지 않고 빈 성공 결과 집합을 반환한다.

---

### User Story 2 - OpenAI·Upstage·Gemini 3모델 구성 + 키 없이도 부팅 안전 (Priority: P2)

운영자는 3개 실모델을 프로퍼티/프로필로 켜고 끌 수 있어야 하고, 키를 하나도 주지 않아도 `:app:batch`(및 기존 `:app:api`)가 정상 부팅해야 한다.

**Why this priority**: US1의 fan-out 이 실제 모델을 물려면 3개 ChatModel 빈 구성이 필요하다. "키 없이 부팅 안전"은 기존 앱 회귀 방지의 필수 안전장치라 P2.

**Independent Test**: 어떤 키도 없이 `:app:batch`(+`:app:api`) 컨텍스트를 기동해 부팅 성공을 확인하고, 키/활성 플래그를 준 프로필에서 3개 ChatModel 빈이 각각 등록되는지 확인한다.

**Acceptance Scenarios**:

1. **Given** API 키·활성 플래그가 전혀 없는 환경, **When** batch·web 앱을 부팅하면, **Then** 컨텍스트 로딩이 실패 없이 완료된다(LLM 빈 0개).
2. **Given** 3모델 키·활성 플래그를 준 프로필, **When** 부팅하면, **Then** 3개 ChatModel 빈이 각각 구성된다(Upstage 는 OpenAI 호환 스타터를 base-url 교체로 재사용, Gemini 는 google-genai 스타터).
3. **Given** 3개 중 일부만 활성화된 프로필, **When** fan-out 클라이언트를 호출하면, **Then** 활성화된 모델만 호출 대상이 된다.

---

### User Story 3 - 실모델 스모크 검증 & 모듈/의존 정합 (Priority: P3)

개발자는 실제 키로 3개 모델이 각각 한 번씩 응답하는지 확인하고, `:app:batch` → `:infra:llm` 의존과 모듈 명명이 코드·설정에 일관되게 반영됐는지 검증하고 싶다.

**Why this priority**: 마감 검증 단계라 P3.

**Independent Test**: 실 키로 3개 모델 각 1회 호출 스모크(또는 문서화된 수동 절차)를 실행하고, `settings.gradle.kts`·`app/batch/build.gradle.kts`·`CLAUDE.md` LLM 라인에서 모듈이 `:infra:llm` 로 일관되며 배치가 이를 의존하는지 확인한다.

**Acceptance Scenarios**:

1. **Given** 유효한 3개 모델 키, **When** 스모크 검증을 실행하면, **Then** OpenAI·Upstage·Gemini 각 모델이 최소 1회 성공 응답을 반환한다(또는 동등한 수동 절차가 문서화된다).
2. **Given** 변경된 코드·설정, **When** 확인하면, **Then** `:app:batch` 가 `:infra:llm` 를 `implementation` 으로 의존하고 현재 구현 직결 파일에서 모듈이 `:infra:llm` 로 일관되며, 결정 근거가 ADR-0010 로 남는다.

---

### Edge Cases

- **모든 모델 실패/전부 비활성**: fan-out 은 예외를 던지지 않고 빈 성공 집합(+실패 모델 목록)을 반환한다. "부분/전체 실패"의 비즈니스 처리는 호출자(후속 배치 잡)의 몫이다.
- **개별 모델 타임아웃**: 해당 모델만 실패로 격리, 다른 모델 결과 수집을 지연·차단하지 않는다.
- **잘못된 키/base-url**: 부팅은 깨지지 않고(none 안전장치 유지), 실제 호출 시 해당 모델만 실패로 분류.
- **모델 일부만 키 존재**: 활성 모델 집합이 곧 fan-out 대상 — 비활성 모델은 조용히 제외.
- **동일 요청의 모델별 응답 형식 차이**: 결과 계약은 모델 식별자와 함께 담아 호출자가 어느 모델 결과인지 구분 가능해야 한다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 LLM 어댑터 모듈 **`:infra:llm`** 를 신설하고 `settings.gradle.kts` 에 등록한다(주석 처리된 `:infra:external` placeholder 자리를 대체). 모듈은 `meogo.spring-conventions` 아키타입을 적용하고 **내부에서 Spring AI 를 의존**한다.
- **FR-002**: 시스템은 fan-out 공개 API(`LlmFanoutClient.generate(request): LlmFanoutResult`)와 요청/응답 값타입을 **`:infra:llm`** 에 둔다. 공개 API 는 **벤더 중립**(Spring AI/벤더 SDK 타입을 시그니처에 노출하지 않음)이어야 한다.
- **FR-003**: **`:app:batch` 가 `:infra:llm` 를 `implementation` 으로 의존**해 잡에서 fan-out 클라이언트를 직접 호출한다. (`:core:kernel` port·runtimeOnly 조립은 생략 — 단일 소비자=배치, 속도 우선. `:application:*` 이나 web 이 LLM 을 재사용해야 할 때 kernel port 로 승격하는 것은 후속.)
- **FR-004**: 시스템은 활성화된 **N개 LLM 모델을 병렬로 호출(fan-out)** 하고, 각 모델 호출을 서로 격리한다.
- **FR-005**: 개별 모델 실패(예외·타임아웃)는 **전체 호출을 중단시키지 않는다**. 실패 모델은 성공 결과 집합에서 제외되고 **어떤 모델이 실패했는지 식별 가능**해야 한다(부분 실패 허용).
- **FR-006**: 시스템은 **OpenAI·Upstage·Gemini** 3개 `ChatModel` 을 명시 빈으로 구성한다. Upstage 는 OpenAI 호환이라 `spring-ai-starter-model-openai` 를 base-url 교체로 재사용, Gemini 는 `spring-ai-starter-model-google-genai` 를 사용.
- **FR-007**: 각 모델 구성은 **프로퍼티(api key·base-url·model name·활성 여부)** 와 **프로필**로 제어된다.
- **FR-008**: **API 키가 하나도 없어도** `:app:batch`(및 `:app:api`) 부팅이 깨지지 않는다(`spring.ai.model.*=none` + 활성 플래그 조건부 빈).
- **FR-009**: 기존 `:app:api`·`:app:batch` 부팅에 **회귀가 없어야** 한다.
- **FR-010**: fan-out 은 **단일모델 seam 인터페이스**(`LlmModelCaller`) 뒤에서 각 모델을 호출해, **실 네트워크 없이 페이크로 병렬·부분실패를 단위 검증**할 수 있어야 한다(헌법 I Test-First).
- **FR-011**: 시스템은 실 키로 3개 모델을 각각 1회 호출하는 **스모크 검증** 또는 **문서화된 수동 절차**를 제공한다.
- **FR-012**: fan-out 클라이언트는 특정 잡·프롬프트에 결합되지 않아 **여러 배치 잡(수집·스코어링, user/admin)이 재사용** 가능해야 한다.
- **FR-013**: 배치는 **단일 `:app:batch` bootJar** 로 두고 잡을 `com.meogo.app.batch.<user|admin>` 패키지로 구분한다. 별도 bootJar 분리는 구체적 운영 드라이버 발생 시 후속(이 기능 범위 밖).
- **FR-014**: 모듈 구조 결정을 **ADR-0010** 으로 남기고, **현재 구현 직결 코드·설정만**(`settings.gradle.kts`, `app/batch/build.gradle.kts`, `CLAUDE.md` 의 LLM 라인) 갱신한다. 범용 `:infra:external` catch-all 서술은 범위 밖.

### Key Entities *(include if feature involves data)*

- **LLM 호출 요청(입력)**: 프롬프트/파라미터 계약. 벤더 SDK 타입에 종속되지 않는 평면 값. (`:infra:llm`)
- **LLM 모델 결과(출력 단위)**: 모델 식별자 + 응답 내용. 어느 모델 결과인지 구분 가능. (`:infra:llm`)
- **병렬 호출 결과 집합**: 성공 모델 결과들 + 실패 모델 식별 목록(부분 실패 표현). (`:infra:llm`)
- **ChatModel 구성**: 모델별 api key·base-url·model name·활성 플래그(프로퍼티/프로필 기반).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 하나의 fan-out 호출로 N개 모델을 병렬 호출했을 때 총 소요가 순차 합이 아니라 **가장 느린 단일 호출에 수렴**한다.
- **SC-002**: N개 중 1개 모델이 실패해도 나머지 성공 모델 결과가 **100% 반환**되고, 실패 모델은 식별 가능한 목록으로 분리된다.
- **SC-003**: API 키를 하나도 설정하지 않은 상태에서 `:app:batch`·`:app:api` 부팅이 **회귀 0**으로 성공한다.
- **SC-004**: 실 키 설정 시 OpenAI·Upstage·Gemini **3개 모델 각각 최소 1회 호출이 성공**한다(또는 문서화된 수동 절차로 확인).
- **SC-005**: `:app:batch` 가 `:infra:llm` 를 `implementation` 으로 의존하고, `settings.gradle.kts`·`app/batch/build.gradle.kts`·`CLAUDE.md` LLM 라인에서 모듈이 **`:infra:llm`** 로 일관된다.

## Assumptions

- 범위는 KB-49(LLM 토대)로 한정 — 실제 프롬프트·집계 전략·배치 잡 구현은 비범위(후속 배치 태스크).
- 모듈명은 **`:infra:llm`**, 내부에서 Spring AI `ChatModel` 을 의존해 호출한다.
- **`:core:kernel` LLM port 와 runtimeOnly 조립을 생략**하고 `:app:batch` 가 `:infra:llm` 를 직접 의존한다(단일 소비자=배치, 속도 우선). web/application 재사용 시 kernel port 승격은 후속.
- 배치는 단일 `:app:batch` 모듈(잡을 user/admin 패키지로 구분).
- LLM 스택은 **Spring AI 2.0**(Boot 4 호환), 카탈로그의 `spring-ai-starter-openai`·`spring-ai-starter-google-genai`.
- 병렬 fan-out 은 **JDK 21 가상 스레드 + CompletableFuture**(독립 리뷰 Codex 확인 — 코루틴 신규 의존 불요).
- "모든 모델 실패/전부 비활성" 시 fan-out 은 예외 없이 빈 성공 집합을 반환(전체 실패 판단은 호출자 책임).
- 작업은 별도 워크트리(`kb-49-llm-client-foundation`, base `develop`)에서 진행.
- 범용 `:infra:external` catch-all 서술과 헌법 명칭 문구 정합은 이 기능 범위 밖(별도 후속).
