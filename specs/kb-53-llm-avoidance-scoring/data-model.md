# Phase 1 Data Model: 기피성분 포함 신뢰도 LLM 스코어링

**Feature**: kb-53-llm-avoidance-scoring | **Date**: 2026-07-06

DB 스키마·JPA 엔티티는 **없다**(영속=KB-54). 아래는 `:core:research` 의 **도메인 값타입**(순수, 불변)과 seam port 다. 모든 타입은 `com.meogo.core.research`(도메인)·`com.meogo.core.food`(seam port)·`com.meogo.core.avoidance`(기존)에 속한다.

## 입력 값타입 (research-local, 원칙 II 격리)

### ScoringFood
| 필드 | 타입 | 규칙 |
|------|------|------|
| `foodId` | `Long` | 결과 역매핑 키(도메인 내부, LLM 미노출) |
| `koreanName` | `String` | blank 금지. 프롬프트 표시·응답 매칭 키 |

### CandidateSubstance
| 필드 | 타입 | 규칙 |
|------|------|------|
| `code` | `String` | `AvoidanceSubstanceCode` 코드 문자열(`^[A-Z0-9_]+$`). 응답 파싱 매칭 키 |
| `koreanLabel` | `String` | DB 카탈로그 ko 원문(`AvoidanceSubstance.name.resolve(ko)`) — enum label 아님(원칙 V) |

> 배치 잡이 `Food → ScoringFood`, `AvoidanceSubstance → CandidateSubstance` 로 매핑한다. 후보 집합은 카탈로그 전체(81, 개수 비하드코딩).

## 파싱 값타입

### SubstanceJudgement (단일 모델의 한 성분 판단)
| 필드 | 타입 | 규칙 |
|------|------|------|
| `code` | `String` | 후보 코드와 일치해야 유효(미지 코드 → FR-009 규칙) |
| `score` | `Int` | 0/1/2. 범위 밖 → FR-009 |
| `probability` | `Int` | 1~100. 범위 밖 → FR-009 |

### ModelScoring (한 모델의 청크 응답 파싱 결과)
| 필드 | 타입 | 규칙 |
|------|------|------|
| `included` | `Map<Long, List<SubstanceJudgement>>` | foodId → 그 음식에서 **포함으로 응답된** 성분들. 응답에 없는 (음식,성분)=미포함 |
| `nameTranslations` | `Map<Long, Map<LanguageCode, String>>` | foodId → 음식명 번역(`ko` 제외 대상 9개 언어 부분집합). 미지 언어·빈값 skip |
| `descriptions` | `Map<Long, LocalizedText>` | foodId → 음식 설명(korean 생성 + translations). 각 값 공백 포함 목표 200·최대 230자(230 초과 잘라내기) |

> 파서는 음식명으로 foodId 를 역매핑한다. 미지 음식명·미지 코드·중복·범위 이탈·형식 오류의 처리는 [contracts/llm-scoring-io.md](./contracts/llm-scoring-io.md) 규칙을 따른다. 파싱 불가(빈/깨진 응답)면 해당 모델을 실패로 격리(그 모델 `ModelScoring` 제외).

## 산출 값타입

### FoodInclusionScore (음식×성분 최종)
| 필드 | 타입 | 규칙 |
|------|------|------|
| `foodId` | `Long` | |
| `substanceCode` | `String` | 후보 코드 |
| `inclusionConfidence` | `Int` | **1~100**(clamp). KB-9 `fromInclusionProbability` 입력 호환 |
| `avgScore` | `Double` | 3개 모델 score 평균(0.0~2.0). 참고·후속 판정용 |
| `avgProbability` | `Double` | 3개 모델 probability 평균(1~100) |
| `agreementFactor` | `Double` | 1.0/0.9/0.75 |

### FoodScoringResult (음식 1건)
| 필드 | 타입 | 규칙 |
|------|------|------|
| `foodId` | `Long` | |
| `status` | `enum SCORED / FAILED` | **3개 모델 모두 취합 시에만 SCORED**(FR-007). 일부라도 실패 → 청크 전체 미확정(FR-008) |
| `scores` | `List<FoodInclusionScore>` | SCORED 일 때 후보 81종 커버(누락=미포함→confidence 1) |
| `nameTranslations` | `Map<LanguageCode, String>` | `ko` 제외 대상 9개 언어 부분집합. `FoodContentSelector` 채택(앙상블 아님). `food.name_translations` 동형 |
| `description` | `LocalizedText` | 한국어 생성 + 9개 언어 번역, 각 공백 포함 목표 200·최대 230자. `food.description`/`description_translations` 동형 |

### FoodContentSelector (도메인 서비스, 앙상블 아님)
- 배치가 우선순위(OPENAI→UPSTAGE→GEMINI) 순으로 정렬한 `List<ModelScoring>` 을 받아, 음식별 **첫 비어있지 않은 모델의 텍스트(이름 번역 + 설명)를 함께 채택**(FR-015, 이름·설명 동일 모델로 일관). research 는 `LlmModelId` 미의존(순서로만 우선순위). 청크 확정 전제(3개 모두 성공)라 통상 최우선(OPENAI) 채택.

> 불변(모든 `val`), Kotlin 주석 금지 규약 준수. 상태 변경 없음(1회 산출).

## Seam Port (신규)

### FoodScoringSource — `com.meogo.core.food`
```
interface FoodScoringSource {
    fun nextChunk(size: Int): List<Food>
}
```
- 조사 대상 음식을 `size`(기본 10) 이하로 공급. 초기 구현: active `food` 읽기(스키마 무변경).
- 어댑터: `:infra:persistence` `FoodScoringSourceAdapter`(읽기 전용). 테스트는 페이크.
- 전용 대기열·재조사 상태는 후속(research.md D6).

## 기존 타입(재사용, 변경 없음)
- `AvoidanceSubstanceRepository.findByCodes(codes)` → 후보 ko 명 로드.
- `AvoidanceSubstanceCode`(81 enum) → 후보 코드 집합(`entries`).
- `:infra:llm` `LlmChatRequest`·`LlmFanoutClient`·`LlmFanoutResult`·`LlmChatResult` — 무변경 사용.
- KB-9 `RiskLevel.fromInclusionProbability`(별개 소비자, KB-53 미호출).

## 검증 규칙 요약 (FR 매핑)
- inclusionConfidence ∈ 1..100 (FR-006, clamp) · score∈0..2·probability∈1..100 파싱 검증(FR-002/009) · 누락=미포함 정규화(FR-003/D4) · **3개 모델 모두 취합 시에만 확정**(FR-007, aggregator `size!=3` 예외) · 일부/전체 실패=모델별 로깅+청크 미확정(FR-008) · 후보=카탈로그 단일출처(FR-010).
- 음식명 번역: 키 ⊆ 대상 9개 언어(`ko` 제외), `LocalizedText.translations` 동형(FR-014) · 앙상블 아님, 우선순위 단일 모델 채택(FR-015) · 미지언어·빈값 skip, 전무 시 `{}`(SC-008).
- 음식 설명: `LocalizedText`(ko 생성 + 9개 번역), 각 공백 포함 목표 200·최대 230자(FR-016/SC-009) · 우선순위 단일 채택(FR-015).
- 모델 완결성: **3개 모두 취합 시에만 확정**(FR-007) · 일부 실패 → 모델별 로깅 + 청크 미확정(FR-008/017, SC-004/010).
