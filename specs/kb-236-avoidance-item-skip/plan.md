# Implementation Plan: 기피성분 조사에서 후보 밖 성분은 항목 단위로 스킵

**Branch**: `kb-236-avoidance-item-skip` | **Date**: 2026-07-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-236-avoidance-item-skip/spec.md`

## Summary

`SpringAiFoodAvoidanceAssessmentClient.parseValidOrNull` 의 검증 정책을 한 가지만 완화한다: 후보 목록(candidateCodes)에 없는 성분 코드 항목은 **응답 전체 무효 대신 해당 항목만 제외(스킵)** 하고, 후보 안 정상 항목·맵기는 그대로 유효 응답으로 종합한다. 나머지 무효 규칙(후보 안 항목의 percent 범위 밖·같은 후보 코드 중복·맵기 범위 밖·JSON 파싱 실패)은 응답 단위 무효를 유지한다. 변경 지점은 `parseValidOrNull` 의 항목 루프 하나이며, 신규 클래스·모듈·스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: 기존 `:infra:llm` 모듈 내부 변경만 — `LlmFanoutClient`(기존), `FoodContentJsonParser`(기존). 신규 의존 없음.

**Storage**: N/A — 저장 구조·Flyway 변경 없음 (종합 결과 `FoodAvoidanceAssessmentResult` 의 저장 경로는 기존 배치 파이프라인 그대로)

**Testing**: Kotest BehaviorSpec (`SpringAiFoodAvoidanceAssessmentClientTest` — 페이크 `LlmModelCaller` 단위 테스트, Spring 컨텍스트 불필요)

**Target Platform**: `:app:batch` bootJar 가 소비 (배치 음식 콘텐츠 파이프라인의 기피성분+맵기 작업)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경은 `:infra:llm` 단일 모듈에 국한

**Performance Goals**: N/A — 파싱 루프의 분기 변경, 성능 특성 불변

**Constraints**: 안전 직결 데이터 — 완화 범위는 "후보 밖 코드" 단 하나로 한정. 후보 안 항목의 무효 신호(percent 범위·중복)는 응답 신뢰성 붕괴 신호로 보고 응답 단위 무효 유지.

**Scale/Scope**: 파일 2개 수정(`SpringAiFoodAvoidanceAssessmentClient.kt` 검증 루프 + `SpringAiFoodAvoidanceAssessmentClientTest.kt` 시나리오 추가/수정). dev 실측 재발 방지(gpt-5-nano 의 OIL·KOREAN_SOYSAUCE·CABBAGE·PEPPER 혼입 → 유효 0/1 → INCOMPLETE 고착).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS (계획 준수) | 기존 BehaviorSpec 에 후보 밖 항목 스킵 시나리오를 먼저 추가·수정해 Red 확인 후 `parseValidOrNull` 을 변경한다. 기존 테스트 1건("후보 밖 코드를 섞어 응답")은 정책 변경으로 기대값이 바뀌므로 새 정책 기준으로 먼저 갱신해 Red 를 만든다. |
| II. Bounded Contexts | PASS | 도메인 모듈 무접촉 — `:infra:llm` 어댑터 내부 검증 정책만 변경. 컨텍스트 간 참조 변화 없음. |
| III. Layered Dependency Direction | PASS | 의존 방향·모듈 그래프 변화 없음. seam(`FoodAvoidanceAssessmentClient`, `:core`) 시그니처 불변. |
| IV. Persistence Ownership | PASS | 영속 코드 무접촉 — 엔티티·리포지토리·트랜잭션 경계·Flyway 변경 없음. |
| V. Domain Content Language Policy | PASS | 안전 직결 데이터의 검수 상태 구분(PENDING_REVIEW 전이)은 기존 파이프라인 그대로. 완화는 "카탈로그에 존재하지 않아 어차피 저장 불가능한 코드"의 처리 방식만 바꾼다 — 저장되는 데이터의 품질 규칙은 불변. |

**게이트 통과** — 위반 없음, Complexity Tracking 불필요.

*Post-Phase 1 재평가*: 설계 산출물(research/data-model/quickstart)이 신규 구조를 도입하지 않음을 확인 — 여전히 PASS.

## Project Structure

### Documentation (this feature)

```text
specs/kb-236-avoidance-item-skip/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

`contracts/` 없음 — 외부 노출 인터페이스(REST API·seam 시그니처) 변경이 없는 내부 검증 정책 변경이다. seam `FoodAvoidanceAssessmentClient.call(koreanName, candidateCodes): FoodAvoidanceAssessmentResult` 는 그대로다.

### Source Code (repository root)

```text
infra/llm/
├── src/main/kotlin/com/kbap/infra/llm/food/
│   └── SpringAiFoodAvoidanceAssessmentClient.kt   # parseValidOrNull 검증 루프 변경 (유일한 프로덕션 변경)
└── src/test/kotlin/com/kbap/infra/llm/food/
    └── SpringAiFoodAvoidanceAssessmentClientTest.kt # 기존 1건 기대값 갱신 + 신규 시나리오 추가
```

**Structure Decision**: 기존 구조 그대로 — 신규 파일·모듈·패키지 없음. 변경은 `:infra:llm` 의 기존 클라이언트와 그 테스트 2개 파일에 국한된다.

## 핵심 설계 결정 (요약 — 상세는 research.md)

1. **스킵이 검증보다 먼저**: 항목 루프에서 `item.code !in candidateCodes` 이면 그 항목을 즉시 건너뛴다(continue). 후보 밖 항목의 percent 값·중복 여부는 검사하지 않는다 — 존재하지 않는 항목 취급.
2. **후보 안 항목의 규칙은 불변**: percent `!in 0..100` 또는 후보 안 코드 중복(`in byCode`) → 기존대로 응답 전체 무효(null).
3. **전 항목 스킵 = 빈 배열과 동일**: 정상 항목 0개여도 맵기가 유효하면 유효 응답(성분 없음 + 맵기 기여). 기존 코드도 빈 assessments 를 유효로 취급하므로 별도 분기 불필요.
4. **종합 로직 불변에 따른 기존 테스트 기대값 변화(의도된 것)**: `aggregateSubstances` 는 유효 응답에 없는 코드를 0 으로 평균한다. 후보 밖 코드 응답이 무효→유효로 승격되면 그 응답이 분모에 들어간다 — 기존 테스트 "한 모델이 후보 밖 코드를 섞어 응답"(3모델, CHICKEN 혼입)의 기대값이 PORK 85(2모델 평균) → **57**((80+90+0)/3 반올림), spiciness 4((3+4+5)/3) 로 바뀐다. 이는 버그가 아니라 정책 변경의 정의된 결과다.

## Complexity Tracking

> 해당 없음 — Constitution Check 위반 없음.
