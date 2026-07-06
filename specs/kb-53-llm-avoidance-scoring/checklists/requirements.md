# Specification Quality Checklist: 기피성분 81종 포함확률 LLM 스코어링

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-07-06
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **[해결됨] 집계 전략** — 2026-07-06 clarify: 참고 문서 §4 Consensus Ensemble 공식 확정(중앙값 가정 폐기). `base_confidence = 0.6·(avg_score/2) + 0.4·(avg_probability/100)` × agreement_factor(1.0/0.9/0.75), `round(·100)`.
- **[해결됨] 프롬프트 세분도** — 2026-07-06 clarify: 음식 10개 청크/1회 호출, 81종 후보 제시 → 포함된 것만 응답(중복 없이).
- **[해결됨] 위험도 판정 권위** — 2026-07-06 clarify: KB-53 은 신뢰도 산출만, 판정은 KB-9(별개). 문서 §6 판정표 미채택.
- **[해결됨] 영속 범위** — 2026-07-06 clarify: DB 저장은 KB-54(T5). KB-53 은 산출 결과까지.
- **[해결됨] 산출값 이름·범위** — 2026-07-06 clarify: `inclusionConfidence` 정수 1~100(프롬프트로 모델 probability 1~100 강제 + 최종 clamp). KB-9 `fromInclusionProbability`(1~100) 호환.
- 구현 관련 용어(`:infra:llm`·`LlmModelCaller`·`AvoidanceSubstanceCode`·`:app:batch`)는 기존 시스템 통합점을 정확히 지목하기 위해 의도적으로 포함(신규 기술 선택이 아니라 기존 계약 참조).
- 남은 plan-단계 결정(블로킹 아님): 프롬프트/파싱/집계 계약의 모듈 배치(벤더 중립 `:infra:llm` vs 도메인성 배치), 조사 대기열 자료구조·재조사 트리거.
- **[변경] `reason` 필드 제거** — 2026-07-06 사용자 지시: LLM 응답 스키마에서 `reason` 제거(집계 미사용). spec·plan·research·data-model·contracts 반영.
- **[추가] 음식명 번역 수신** — 2026-07-06 사용자 지시: 같은 LLM 호출에서 음식명의 대상 9개 언어 번역을 JSON(`nameTranslations`, `food.name_translations` 동형)으로 수신(FR-014/015, SC-008). 번역은 앙상블 아님·우선순위 단일 모델 채택.
- **[변경] 3개 모델 모두 취합 필요** — 2026-07-06 사용자 지시: 청크 확정에 3개 모델 결과 모두 필요(부분 집계 금지), 일부 실패는 모델별 별도 로깅 + 청크 미확정(FR-007/008/017, SC-004/010). ⚠️ **기존 clarify "≥1 성공 완결" 및 KB-53 Jira DoD "일부 실패해도 완결"을 대체 — Jira DoD 갱신 필요.** aggregator 는 `perModel.size!=3` 시 예외.
- **[추가] 음식 설명 생성·저장** — 2026-07-06 사용자 지시: 음식 설명(한국어 LLM 생성 + 9개 언어 번역, 각 공백 포함 목표 200·하드캡 230자, `food.description`/`description_translations`·`LocalizedText` 동형) 산출(FR-016, SC-009). 우선순위 단일 모델 채택(이름·설명 동일 모델).
- **확인 필요 3건**: (1) 텍스트 선정 우선순위(기본 OPENAI→UPSTAGE→GEMINI), (2) 언어 수 — "10개국"을 헌법 V 9개 대상 언어(+ko=10)로 해석, (3) 3-모델-필수 정책의 Jira DoD 반영.
