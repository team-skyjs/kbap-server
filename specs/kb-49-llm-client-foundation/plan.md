# Implementation Plan: LLM 호출 토대 — `:infra:llm` 모듈, 배치가 직접 의존

**Branch**: `kb-49-llm-client-foundation` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-49-llm-client-foundation/spec.md`

## Summary

배치(수집·스코어링)가 쓸 **LLM 호출 토대**를 신설 **`:infra:llm`** 모듈에 구축한다. 모듈은 Spring AI `ChatModel` 을 의존해 OpenAI·Upstage·Gemini 3개 모델을 프로퍼티/프로필로 명시 구성하고(키 없으면 빈 미생성 → 부팅 안전), 활성 모델들을 **JDK 21 가상 스레드로 병렬 fan-out**하며 개별 모델 실패를 격리해 성공분만 모은다. 공개 API(`LlmFanoutClient`)와 요청/응답 값타입은 `:infra:llm` 안에 두고 벤더 중립으로 노출한다. **`:app:batch` 가 `:infra:llm` 를 `implementation` 으로 직접 의존**해 잡에서 호출한다 — `:core:kernel` port 와 runtimeOnly 조립은 **생략**(단일 소비자=배치, 속도 우선). 배치는 단일 bootJar 로 두고 잡을 user/admin 패키지로 구분. 결정은 ADR-0010 으로 남긴다.

## Technical Context

**Language/Version**: Kotlin 2.3.21 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0 BOM — `spring-ai-starter-model-openai`(OpenAI + Upstage base-url 재사용) · `spring-ai-starter-model-google-genai`(Gemini). 병렬성은 JDK 21 `Executors.newVirtualThreadPerTaskExecutor()` + `CompletableFuture`(신규 의존 없음).

**Storage**: N/A — 영속 계층 무변경(순수 외부 호출 어댑터).

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어) + JUnit platform. fan-out 부분실패는 페이크 `LlmModelCaller` 로 단위 검증, 부팅 안전·빈 배선은 `@SpringBootTest`(SpringExtension), 실키 스모크는 `@Disabled` + 문서화된 수동 절차.

**Target Platform**: Linux server (JVM bootJar — `:app:batch` batch, `:app:api` web).

**Project Type**: 모듈러 모놀리스 백엔드(Gradle 멀티모듈, ADR-0008).

**Performance Goals**: N개 모델 병렬 호출 총 소요가 순차 합이 아니라 **가장 느린 단일 호출에 수렴**.

**Constraints**: 공개 API 벤더 중립(Spring AI 타입 미노출 — 어댑터 내부 격리). 키 없이 batch/web 부팅 무회귀. 개별 모델 실패 격리(부분 실패 허용). fan-out 은 seam 인터페이스 뒤에서 호출해 페이크로 단위 검증 가능(헌법 I).

**Scale/Scope**: 현재 3모델, N 일반화 가능(모델 추가 = enum 상수 + 빈 1개). 산출물 — 신규 모듈 1(`:infra:llm`) + 배치 의존 배선 1 + ADR 1 + 최소 문서 정합.

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 설계 후 재검증.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ PASS | 구현 전 실패 테스트 선작성: fan-out 부분실패(페이크 `LlmModelCaller`), 부팅 안전(키 없음 컨텍스트 로딩), 빈 배선, 프로퍼티 바인딩. 실키 스모크는 `@Disabled`+수동. seam 인터페이스로 실 네트워크 없이 Red→Green. |
| **II. Bounded Contexts** | ✅ PASS | LLM 호출은 도메인 컨텍스트가 아니라 **횡단 인프라 어댑터**(`:infra:llm`). 도메인↔도메인 결합 없음. |
| **III. Layered Dependency Direction** | ✅ PASS(주의) | `:app:batch`(부트앱, 최상위) → `:infra:llm`(implementation). **부트앱이 인프라 어댑터를 직접 조립·호출하는 것은 허용**(app 은 top layer). ⚠️ 단, `:application:*` 은 인프라 구현에 의존 금지(원칙 III) — **향후 `:application:batch` 유스케이스가 LLM 을 조율해야 하면 그때 `:core:kernel` port 로 승격**(현재는 잡이 부트앱에서 직접 호출하므로 미도입). |
| **IV. Persistence Encapsulation** | ✅ N/A | JPA/영속 코드 없음. |
| **V. Domain Content Language Policy** | ✅ N/A | 콘텐츠/번역/폴백 없음(토대만). |

**게이트 결과: PASS.** 원칙 III 는 부트앱→인프라 직접 의존이라 위반 아님. "application→infra 금지"는 유지되며, 그 경계가 필요한 시점(application 유스케이스 도입)에 kernel port 승격을 트리거로 명시(아래 Complexity Tracking 대신 설계 노트로 기록).

## Project Structure

### Documentation (this feature)

```text
specs/kb-49-llm-client-foundation/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 동시성·부팅안전·배치 의존·모듈 결정
├── data-model.md        # Phase 1 — :infra:llm 값타입 + 구성요소
├── quickstart.md        # Phase 1 — 활성화·키·스모크·부팅안전 확인
├── contracts/
│   ├── llm-fanout-client.md      # :infra:llm 공개 API(LlmFanoutClient) 계약
│   └── llm-client-properties.md  # meogo.llm.* 프로퍼티 스키마
└── checklists/requirements.md
```

### Source Code (repository root)

```text
infra/llm/                                              # 신규 모듈(:infra:llm, meogo.spring-conventions)
├── build.gradle.kts        # spring-ai 스타터(implementation)
└── src/main/kotlin/com/meogo/infra/llm/
    ├── LlmFanoutClient.kt          # 공개 API — generate(request) → LlmFanoutResult (가상스레드 fan-out + 부분실패 격리)
    ├── LlmChatRequest.kt           # 입력 값객체(prompt + 선택 파라미터)
    ├── LlmModelId.kt               # 모델 식별 enum: OPENAI, UPSTAGE, GEMINI
    ├── LlmChatResult.kt            # 단일 모델 성공(modelId + content)
    ├── LlmModelFailure.kt          # 단일 모델 실패(modelId + message)
    ├── LlmFanoutResult.kt          # successes + failures 집계
    ├── LlmModelCaller.kt           # 단일모델 seam(테스트 페이크 대상)
    ├── SpringAiModelCaller.kt      # ChatModel 1개 래핑 → LlmModelCaller 구현
    ├── LlmModelProperties.kt       # @ConfigurationProperties("meogo.llm")
    └── LlmConfiguration.kt         # 3 caller 빈 @ConditionalOnProperty + executor + LlmFanoutClient 빈
└── src/test/kotlin/com/meogo/infra/llm/
    ├── LlmFanoutClientTest.kt          # 병렬·부분실패·전멸 단위(페이크)
    ├── LlmModelPropertiesBindingTest.kt
    ├── LlmConfigurationBootSafetyTest.kt  # 키 없음 → 컨텍스트 로딩/빈 미생성
    └── LlmSmokeTest.kt                 # @Disabled 실키 3모델 1회 호출

app/batch/build.gradle.kts   # + implementation(project(":infra:llm")) ; 낡은 "디커플드" 주석 정정(ADR-0008)
app/batch/src/main/resources/application.yml  # spring.ai.model.*=none 추가(스타터 유입 대비)
settings.gradle.kts          # ":infra:external" 주석 → include(":infra:llm")

# 결정 기록 + 최소 문서 정합(현재 구현 직결분만 — 2026-07-06 clarify)
docs/adr/0010-llm-adapter-module-named-infra-llm.md   # 신규 ADR — :infra:llm + 배치 직접 의존(kernel port·runtimeOnly 생략)
CLAUDE.md(LLM 기술스택·모듈 라인) · settings.gradle.kts · app/batch/build.gradle.kts
                              # 범용 :infra:external catch-all 서술(ADR-0008·architecture 문서·email·MQ)은 범위 밖(그대로 둠)
```

**Structure Decision**: LLM 호출을 **인프라 어댑터 모듈 `:infra:llm` 하나**에 응집한다(공개 API·값타입·Spring AI 구성·fan-out 전부). `:app:batch` 가 이 모듈을 `implementation` 으로 직접 의존해 잡에서 호출한다 — 단일 소비자(배치)라 kernel port·runtimeOnly 조립의 간접층을 생략해 속도를 얻는다. 대신 fan-out 을 **단일모델 seam(`LlmModelCaller`)** 뒤에 두어 실 네트워크 없이 부분실패를 단위 검증(헌법 I)한다. `:app:api` 는 이번 범위에서 LLM 을 의존하지 않는다(web 재사용은 후속 — 그때 kernel port 승격 검토). 배치는 단일 bootJar, 잡은 user/admin 패키지로 구분.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성 불요. (원칙 III 경계는 "application 유스케이스 도입 시 kernel port 승격" 트리거로 설계 노트에 명시.)
