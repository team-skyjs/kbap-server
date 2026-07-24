# Implementation Plan: 기피성분 포함 신뢰도 LLM 스코어링 (3모델 병렬 → Consensus Ensemble)

**Branch**: `kb-53-llm-avoidance-scoring` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-53-llm-avoidance-scoring/spec.md`

## Summary

조사 대기열의 음식을 **10개 청크**로 묶어 3개 LLM(KB-49 `:infra:llm` fan-out)에 병렬로 넘기고, 각 모델이 대표 레시피 기준 **포함으로 판단한 (음식, 성분)만** `score(0/1/2)·probability(1~100)` 으로 응답하게 한 뒤, 3개 응답을 **Consensus Ensemble 공식**(참고 문서 §4)으로 (음식, 성분)별 최종 `inclusionConfidence`(정수 1~100)로 종합한다. **3개 모델이 모두 취합돼야 확정**하며, 일부 실패(API 다운 등)는 **모델별 별도 로깅** + 청크 미확정(재조사). **같은 호출에서 음식명 9개 언어 번역 + 음식 설명(ko 생성 + 9개 번역, 공백 포함 ≤200자, 헌법 V)도 JSON 으로 함께 받아**(기존 `food.name_translations`·`description(_translations)` 동형) 우선순위 단일 모델 채택으로 산출한다(텍스트는 앙상블 아님).

**핵심 설계**: LLM 상호작용의 도메인 로직(프롬프트 구성·응답 파싱·앙상블 종합)을 **`:core:research`(순수 도메인, Spring/ORM-free)** 에 응집한다(ADR-0004 — research=조사·종합 파이프라인, 종합 판단은 순수 도메인 서비스). `:infra:llm` 은 벤더 중립 그대로 두고(도메인 미유입), 실제 조율(대기열 읽기 → LLM 호출 → research 종합 → 결과 산출)은 **`:app:batch` 잡**이 얇게 수행한다(ADR-0010/KB-49 배치-직접 선례). **위험도 판정(KB-9)·DB 영속(KB-54)은 범위 밖** — KB-53 은 저장 직전의 산출 결과까지만 책임진다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: KB-49 `:infra:llm`(`LlmFanoutClient.generate(LlmChatRequest) → LlmFanoutResult`, JDK21 가상스레드 fan-out·부분실패 격리·타임아웃). JSON 파싱은 이미 스택에 있는 `jackson-module-kotlin`(spring-conventions). 신규 서드파티 의존 없음.

**Storage**: 신규 스키마·엔티티·마이그레이션 **없음**(영속은 KB-54/T5). 예외 — 음식 공급 seam 을 위한 **읽기 전용 port 메서드 1개**(`:core:food`)와 그 어댑터 구현(`:infra:persistence`), 스키마 무변경.

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어). research 순수 서비스(프롬프트·파서·앙상블)는 실 네트워크 없이 단위 검증. 배치 잡은 페이크 `LlmFanoutClient`+페이크 port 로 검증. 공식 재현은 문서 §5 예시(`비빔밥-계란`→74) 골든 테스트.

**Target Platform**: Linux server — `:app:batch` bootJar.

**Project Type**: 모듈러 모놀리스 백엔드(Gradle 멀티모듈, ADR-0008).

**Performance Goals**: 청크(10 음식)당 3모델 병렬 호출의 벽시계가 순차 합이 아니라 가장 느린 단일 모델에 수렴(KB-49 fan-out 재사용).

**Constraints**: `:infra:llm` 벤더 중립 유지(도메인 타입 미유입). `:core:research` 는 타 도메인(core:food/avoidance) 타입을 import 하지 않고 **자체 primitive 값타입**(음식명·성분코드/라벨 문자열)으로만 동작(원칙 II 디커플). 최종값 정수 1~100 clamp(KB-9 계약 호환). **3개 모델 모두 취합 시에만 확정**, 일부 실패=모델별 별도 로깅 + 청크 미확정(재조사).

**Scale/Scope**: 후보 성분 81종(카탈로그 단일 출처, 개수 비하드코딩). 청크 10 음식/호출. 모델 3개(N 일반화 가능). 음식명 번역 대상 9개 언어(헌법 V). 산출물 — `:core:research` 신규 도메인 코드(스코어 + 번역 선정) + `:core:food` 읽기 port 1 + `:app:batch` 잡·배선 + 최소 문서.

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 설계 후 재검증.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ PASS | 구현 전 실패 테스트 선작성. research 순수 서비스(프롬프트 구성·응답 파싱·앙상블 종합)를 페이크 없이 순수 단위검증(입력 문자열/값 → 출력). 공식은 문서 §5 골든 예시로 결정성 고정(SC-003). 배치 잡은 페이크 `LlmFanoutClient`/port 로 부분실패·전멸·청크 분할 Red→Green. |
| **II. Bounded Contexts** | ✅ PASS (주의) | 스코어링 **도메인 로직은 `:core:research` 단일 컨텍스트**에 응집(순수 서비스, ADR-0004). research 는 food/avoidance 타입을 직접 참조하지 않고 primitive 값타입으로만 동작(컨텍스트 격리). ⚠️ **컨텍스트 조합(food·avoidance port 입력 수집 + LLM IO)은 `:application:*` 아닌 `:app:batch` 잡에서** 수행 — 원칙 II "조합은 application 에서만"의 예외. 배치 잡엔 **새 도메인 규칙을 두지 않고**(로직은 research), 얇은 조율만 둔다. 정당화·승격 트리거는 Complexity Tracking. |
| **III. Layered Dependency Direction** | ✅ PASS | `:app:batch`(최상위) → `:core:{research,food,avoidance}`·`:infra:llm`(implementation) + `:infra:persistence`(runtimeOnly). 도메인 모듈은 `:core:kernel` 만 바라봄(research↔타 도메인 직접 의존 없음). application→infra 금지 위반 없음(그 계층 미도입). |
| **IV. Persistence Encapsulation** | ✅ PASS | 신규 엔티티/스키마/마이그레이션 없음(영속은 KB-54). 음식 공급 seam 은 `:core:food` port(도메인) + `:infra:persistence` 어댑터 구현으로, 도메인은 ORM-free 유지, 상위는 port 로만 사용. |
| **V. Domain Content Language Policy** | ✅ PASS | 프롬프트의 성분명은 enum `AvoidanceSubstanceCode.label`(비권위·개발자용)이 아니라 **DB 카탈로그 ko 원문**(`AvoidanceSubstance.name`, 단일 출처)을 사용. 음식명은 `Food.displayName(ko)`. **음식명 번역·음식 설명(ko 생성+9개 번역)을 배치가 LLM 으로 생성**(헌법 V 정합 — 콘텐츠는 ko 원문+9개 대상 언어 사전번역, 배치 생성)하며 미보유 언어는 ko 폴백·산출물은 `LocalizedText` 동형. 음식명·설명은 알러지/식이 안전 직결 데이터가 아니므로 별도 검수상태 미도입(성분 판정과 분리 — 단, 부분 결과 불신 정책상 3개 모델 취합 시에만 확정). |

**게이트 결과: PASS.** 원칙 II 의 "조합 위치" 예외만 존재 — 아래 Complexity Tracking 에 정당화.

## Project Structure

### Documentation (this feature)

```text
specs/kb-53-llm-avoidance-scoring/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 모듈 배치·프롬프트/응답 포맷·앙상블 일반화·누락처리·음식 seam 결정
├── data-model.md        # Phase 1 — research 값타입 + 음식 공급 port + 산출 결과 타입
├── quickstart.md        # Phase 1 — 스코어링 잡 실행·활성화·페이크 테스트·공식 재현 확인
├── contracts/
│   ├── scoring-pipeline.md    # :core:research 공개 API(프롬프트 팩토리·파서·앙상블) 계약
│   ├── llm-scoring-io.md      # LLM 프롬프트 지시·JSON 응답 스키마(포함된 것만) 계약
│   └── food-scoring-source.md # 음식 공급 seam port 계약
└── checklists/requirements.md
```

### Source Code (repository root)

```text
core/research/                                          # 스코어링 도메인(신규 코드, meogo.domain-conventions)
└── src/main/kotlin/com/meogo/core/research/
    ├── ScoringFood.kt                 # 값타입 — (foodId, koreanName) research-local
    ├── CandidateSubstance.kt          # 값타입 — (code, koreanLabel) research-local
    ├── SubstanceJudgement.kt          # 단일 모델의 (성분코드, score 0/1/2, probability 1~100)
    ├── ModelScoring.kt                # 한 모델의 파싱 결과(음식 → 포함 성분 판단 목록 + 음식명 번역 + 설명)
    ├── FoodInclusionScore.kt          # 최종 (foodId, substanceCode, inclusionConfidence 1~100, avgScore, avgProbability, agreementFactor)
    ├── FoodScoringResult.kt           # 음식 1건 결과(scores + nameTranslations + description(LocalizedText) + SCORED/FAILED)
    ├── ScoringPromptFactory.kt        # 순수 — (foods, candidates) → ScoringPrompt(prompt, system) ; 번역 9개 언어 + 설명(≤200자) 지시 포함
    ├── ScoringResponseParser.kt       # 순수 — 원시 content → ModelScoring (성분 누락=미포함 + 번역·설명 파싱·230자 초과 잘라내기, FR-009)
    ├── ConsensusEnsembleAggregator.kt # 순수 — 3개 ModelScoring → List<FoodScoringResult> (문서 §4, perModel.size!=3 예외·clamp)
    └── FoodContentSelector.kt         # 순수 — 우선순위 정렬 ModelScoring → 음식별 이름 번역·설명 단일 채택(앙상블 아님, FR-015)
└── src/test/kotlin/com/meogo/core/research/
    ├── ScoringPromptFactoryTest.kt
    ├── ScoringResponseParserTest.kt        # 정상 부분응답·범위이탈·미지코드·형식오류·빈응답·번역·설명 파싱·230자 초과 잘라내기
    ├── ConsensusEnsembleAggregatorTest.kt  # 문서 §5 골든(74)·agreement·perModel!=3 예외·0→1 clamp
    └── FoodContentSelectorTest.kt          # 우선순위 첫 비어있지 않은 채택(이름·설명 동일 모델)·전무 빈값·결정성

core/food/src/main/kotlin/com/meogo/core/food/
└── FoodScoringSource.kt   # 신규 port(seam) — 조사 대상 음식 청크 공급(초기 구현=active food 읽기)

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
└── FoodScoringSourceAdapter.kt  # 신규 어댑터 — 읽기 전용 조회(스키마 무변경)
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/
└── FoodScoringSourceAdapterTest.kt   # MySQL Testcontainers

app/batch/build.gradle.kts   # + implementation(:core:research, :core:food, :core:avoidance)
                             # + runtimeOnly(:infra:persistence) ; 기존 :infra:llm 유지
app/batch/src/main/kotlin/com/meogo/app/batch/scoring/
├── AvoidanceScoringJob.kt        # 조율 — 대기열→청크(10)→llm.generate→[successes==3 확인, 아니면 실패 로깅+미확정]→research 파싱·종합·텍스트 선정→결과 산출
└── ScoringJobConfig.kt           # 빈 배선(페이크 대체 가능한 seam 구성)
app/batch/src/main/resources/application.yml   # meogo.llm.* 스코어링 활성 프로필(키는 .env), 청크 크기 프로퍼티(기본 10)
app/batch/src/test/kotlin/com/meogo/app/batch/scoring/
└── AvoidanceScoringJobTest.kt    # 페이크 LlmFanoutClient+페이크 FoodScoringSource 로 종단·부분실패·전멸

# 문서
docs/adr/0011-scoring-domain-in-research-batch-orchestration.md  # (선택) research 배치·조합 위치 결정 기록
CLAUDE.md(SPECKIT 포인터)
```

**Structure Decision**: 스코어링 **도메인 로직(프롬프트·파싱·앙상블)을 `:core:research` 순수 서비스**로 응집하고(ADR-0004 정합, 단위테스트 용이·헌법 I), `:infra:llm` 은 벤더 중립 전송 계층 그대로 재사용한다. **조율은 `:app:batch` 잡**이 얇게 수행하며(ADR-0010/KB-49 배치-직접 선례), food·avoidance port 로 입력을 모아 research 에 primitive 로 넘긴다(research↔도메인 디커플). 음식 공급은 **port seam** 으로 두어 테스트에서 페이크로 대체하고, 전용 대기열 테이블은 후속(재조사 상태·재시도 필요 시)으로 연기한다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 컨텍스트 조합을 `:application:*` 아닌 **`:app:batch` 잡**에서 수행(원칙 II 예외) | 단일 소비자=배치. KB-49/ADR-0010 이 배치→`:infra:llm` 직접 호출을 이미 채택(선례). `:application:batch` 미존재. 도메인 로직은 research 에 있고 잡은 얇은 조율만. | **지금 `:application:batch` 신설**은 유스케이스 1개엔 과함 — ADR-0004 §6 승격 트리거(①배치 유스케이스 다수, ②독립 CD 엄격, ③api·batch 공유 application) 미도달. 트리거 충족 시 조합을 `:application:batch` 로 승격(잡은 트리거만 유지). |
