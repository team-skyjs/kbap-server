# Implementation Plan: LLM 스코어링 호출 비용 절감 — 호출당 ₩1 미만 (프롬프트 압축·텍스트 역할 분리)

**Branch**: `kb-93-llm-scoring-cost-reduction` | **Date**: 2026-07-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-93-llm-scoring-cost-reduction/spec.md`

## Summary

KB-53 스코어링 파이프라인의 호출 비용(실측 OpenAI ≈₩10 · Gemini ≈₩6.3 · Upstage ≈₩0.9)을 **산출물·정확도 불변**으로 청크당·모델당 ₩1 미만으로 낮춘다. 세 갈래: (1) **포맷 압축** — 프롬프트에서 음식·후보 성분을 인덱스로 열거(후보는 코드만), 응답을 `[음식인덱스, 성분인덱스, score, probability]` 배열로 수신, (2) **텍스트 역할 분리** — 음식 설명(출력 토큰의 80%+)을 모든 모델 프롬프트에서 완전 제거(별도 티켓 이관), 음식명 9개 언어 번역은 Gemini 1개 모델 호출에만 포함(청크당 모델별 호출 1회 유지), (3) **모델·옵션 튜닝** — gpt-5-nano + 추론 노력 최소 + 전 모델 출력 토큰 상한 + [정적 프리픽스(시스템 지시·후보 81종) → 동적(음식 청크)] 배치로 프리픽스 캐싱 정렬. 앙상블 공식·3모델 전량 취합 확정 규칙·`inclusionConfidence` 의미는 KB-53 그대로다.

구현 접점: `:core:research` 의 `ScoringPromptFactory`(모델 역할별 압축 프롬프트 변형)·`ScoringResponseParser`(인덱스 배열 파서, KB-53 판단 규칙 의미 보존), `:infra:llm` 의 `LlmFanoutClient`(모델별 요청 분기 seam)·`LlmConfiguration`/`LlmModelProperties`(출력 상한·추론 노력 옵션), `:app:batch` 의 `AvoidanceScoringJob`(역할별 프롬프트 2종 구성·분기 호출). `FoodContentSelector`·`ConsensusEnsembleAggregator`·`ModelScoring` 데이터 형태는 유지된다(설명은 항상 미산출 → selector 가 null 안전 산출).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(`spring-ai-starter-model-openai` + `spring-ai-starter-model-google-genai`, `:infra:llm` 소유), Jackson(`jackson-module-kotlin`, `:core:research` 파서)

**Storage**: N/A — 이 기능은 산출까지(DB 영속은 KB-54 범위 밖). DB 접점은 기존 `FoodScoringSource`·`AvoidanceSubstanceRepository` 읽기뿐이며 무변경

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어) + JUnit 5 플랫폼. 프롬프트/파서는 골든 테스트 갱신(FR-012), fan-out·옵션 바인딩은 페이크/컨텍스트 테스트, 비용 실측은 수동 스모크(`AvoidanceScoringSmokeTest` + 토큰/비용 로그)

**Target Platform**: `:app:batch` bootJar (JVM 21, Linux/macOS 서버)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 기존 모듈 `:core:research` / `:infra:llm` / `:app:batch` 만 수정, 신규 모듈 없음

**Performance Goals**: 청크(음식 10개) 스코어링 시 3개 모델 각각의 1회 API 호출 비용 < ₩1 (환율 1 USD = 1,500 KRW 고정, 토큰/비용 로그 실측 — SC-001). 프리픽스 캐싱은 추가 마진(미적용이어도 목표 충족)

**Constraints**: 청크당 모델별 API 호출 정확히 1회 유지(FR-006) · (음식,성분)별 최종 판단 결과 KB-53 과 동일(FR-003/SC-005) · 스코어링 호출 출력에 음식 설명 0(SC-002) · 이름 번역은 정확히 1개 모델(Gemini) 수신(SC-003) · 청크 확정 게이트는 스코어링 3모델 전량 취합만(FR-008)

**Scale/Scope**: 음식 청크 10개 × 후보 성분 81종(조합 810/청크) × 3모델. 변경 파일 ~10개(main) + 테스트 갱신 ~8개. US4 는 코드 아닌 Jira 티켓 등록

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 원칙 | 판정 | 근거 |
|---|------|------|------|
| I | Test-First (NON-NEGOTIABLE) | ✅ PASS | 압축 프롬프트 골든 테스트·인덱스 파서 단위 테스트·역할 분리(모델별 프롬프트 지시문 유무)·selector null 안전·fan-out 모델별 분기·옵션 바인딩 테스트를 **구현 전 Red 로 먼저 작성**. 기존 골든 테스트는 압축 포맷으로 갱신(FR-012). 실 네트워크는 스모크(수동)만 |
| II | Bounded Contexts | ✅ PASS | 스코어링 도메인 로직은 `:core:research` 순수 서비스 유지 — food/avoidance 타입 미import, `ScoringFood`·`CandidateSubstance` primitive 값타입 격리(KB-53 동일). 컨텍스트 조합을 `:app:batch` 잡에서 하는 것은 KB-53 에서 승인된 원칙 II 예외(ADR-0004 §6 승격 트리거 유지) — 이 기능은 그 구조를 바꾸지 않는다 |
| III | Layered Dependency Direction | ✅ PASS | 의존 방향 신규 추가 없음. `:app:batch` → `:core:research`·`:infra:llm`(implementation) 은 ADR-0010 기존 결정. `:infra:llm` 변경(모델별 요청 seam·옵션)은 벤더 중립 공개 API 확장이며 역방향 의존을 만들지 않는다 |
| IV | Persistence Encapsulation | ✅ PASS | 영속 코드 무변경. 엔티티/리포지토리/마이그레이션 없음 |
| V | Domain Content Language Policy | ✅ PASS (주의 기록) | ko 원문 + 9개 언어 사전 번역·저장 정책 자체는 불변. 이 기능은 **산출 경로만 재배치** — 설명 생성·번역은 별도 티켓으로 이관(FR-013, US4)하고, 이름 번역은 단일 모델 best-effort(KB-53 에서도 best-effort 병합)로 유지. 스코어링(안전 직결)과 달리 번역·설명은 확정 게이트가 아니라는 KB-53 결정을 계승. 저장(KB-54)은 설명 부재를 허용해야 한다(전제 명시) |

**추가 제약 검토**: "외부 LLM 호출을 DB 트랜잭션 안에서 길게 잡지 않는다" — 기존 잡 구조(읽기 → fan-out → 산출) 유지, 위반 없음. "도메인/영속 모델을 API 응답으로 노출하지 않는다" — web 접점 없음.

**Post-Phase 1 재평가**: 설계 산출물(research.md·data-model.md·contracts/) 확정 후 재점검 — 위반 없음 확인(2026-07-07). `LlmFanoutClient` 의 모델별 요청 분기는 함수 seam(`(LlmModelId) -> LlmChatRequest`)으로 벤더 중립을 유지하고, `:core:research` 는 여전히 Spring-free·타 컨텍스트 미import 다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-93-llm-scoring-cost-reduction/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── compressed-scoring-llm-contract.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
core/research/src/main/kotlin/com/meogo/core/research/
├── prompt/
│   ├── ScoringPromptFactory.kt      # [수정] 압축 포맷 + 역할별 변형(이름 번역 포함 여부) + 정적→동적 순서
│   └── ScoringPrompt.kt             # [유지] (prompt, system) 값타입
├── parse/
│   ├── ScoringResponseParser.kt     # [수정] 인덱스 배열 파서로 교체 — KB-53 판단 규칙 의미 보존
│   ├── ModelScoring.kt              # [유지] descriptions 는 항상 비어 있게 됨(형태 유지)
│   ├── SubstanceJudgement.kt        # [유지]
│   └── ScoringResponseParseException.kt  # [유지]
├── input/                           # [유지] ScoringFood·CandidateSubstance
└── ensemble/                        # [유지] FoodContentSelector(빈 설명 → null 기존 동작)·Aggregator·공식

core/research/src/test/kotlin/com/meogo/core/research/
├── prompt/ScoringPromptFactoryTest.kt   # [갱신] 압축 포맷 골든 — 인덱스 열거·코드 전용·역할별 지시문 유무·정적→동적 순서
├── parse/ScoringResponseParserTest.kt   # [갱신] 배열 파싱·범위/인덱스 이탈·중복·커버리지·KB-53 동등성
└── ensemble/FoodContentSelectorTest.kt  # [보강] 단일 소스 이름 번역 병합·설명 전무 → null

infra/llm/src/main/kotlin/com/meogo/infra/llm/
├── client/LlmFanoutClient.kt        # [수정] 모델별 요청 분기 generate((LlmModelId) -> LlmChatRequest) 추가
├── config/
│   ├── LlmModelProperties.kt        # [수정] max-output-tokens·reasoning-effort 프로퍼티 추가
│   └── LlmConfiguration.kt          # [수정] 옵션을 OpenAI/Upstage/Gemini ChatOptions 로 배선
└── (model/·provider/ 유지 — 토큰/비용 로그는 SpringAiModelCaller 기존 구현 재사용)

infra/llm/src/test/kotlin/com/meogo/infra/llm/
├── client/LlmFanoutClientTest.kt            # [보강] 모델별 상이 요청 전달 검증(호출 수 1회/모델 유지)
└── config/LlmModelPropertiesBindingTest.kt  # [보강] 신규 옵션 바인딩
   (LlmConfiguration*Test.kt — 옵션 배선 검증 보강)

app/batch/src/main/kotlin/com/meogo/app/batch/scoring/
├── AvoidanceScoringJob.kt           # [수정] 역할별 프롬프트 2종 구성 → 모델별 분기 fan-out 호출
└── ScoringJobConfig.kt              # [수정 최소] 빈 배선(프롬프트 팩토리 시그니처 변경 반영)

app/batch/src/main/resources/application.yml  # [수정] max-output-tokens·reasoning-effort 기본값(모델별)

app/batch/src/test/kotlin/com/meogo/app/batch/scoring/
├── AvoidanceScoringJobTest.kt       # [갱신] 페이크 fan-out — Gemini 만 번역, 설명 전무, 확정 게이트 불변
└── AvoidanceScoringSmokeTest.kt     # [유지] 비용 실측 수단(SC-001, 수동 실행)
```

**Structure Decision**: 신규 모듈·신규 클래스 최소화 — KB-53 이 만든 계약 클래스(`ScoringPromptFactory`·`ScoringResponseParser`)를 **제자리 교체**한다(구 포맷은 프롬프트 교체와 동시에 사장되므로 병행 유지 무의미). KB-53 동등성(SC-005)은 구 파서 보존이 아니라 **동일 판단을 두 포맷 픽스처로 표현한 골든 동등성 테스트**로 검증한다. 모델별 프롬프트 분기는 `:infra:llm` 에 벤더 중립 함수 seam 으로 두고, 어떤 모델이 번역 담당인지는 `:app:batch` 조율 계층만 안다(`:core:research` 는 "이름 번역 포함 여부" 불리언만 인지 — 모델명 미인지).

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.

## Phase 0: Research (완료 — [research.md](research.md))

해결한 결정: R1 압축 응답 스키마·커버리지 신호, R2 이름 번역 압축 포맷(고정 언어 순서), R3 모델별 요청 분기 seam 위치, R4 출력 상한·추론 노력 옵션의 벤더별 매핑, R5 프리픽스 캐싱 정렬 방식, R6 KB-53 파서 동등성 검증 전략, R7 비용 로그 재사용. 전부 research.md 에 Decision/Rationale/Alternatives 로 기록. NEEDS CLARIFICATION 잔여 없음.

## Phase 1: Design & Contracts (완료)

- [data-model.md](data-model.md) — 압축 프롬프트·인덱스 판단 항목·ModelScoring(유지)·FoodContent(유지)·호출 비용 로그 엔티티 정의
- [contracts/compressed-scoring-llm-contract.md](contracts/compressed-scoring-llm-contract.md) — 모델 역할별 프롬프트 구조(정적/동적)·압축 응답 JSON 스키마·파싱 규칙(이탈/중복/커버리지)·fan-out 모델별 요청 seam
- [quickstart.md](quickstart.md) — 테스트 실행·실측(스모크) 방법
- 에이전트 컨텍스트: 루트 `CLAUDE.md` SPECKIT 마커의 현재 plan 포인터를 본 파일로 갱신
