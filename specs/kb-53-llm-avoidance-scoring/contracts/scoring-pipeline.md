# Contract: `:core:research` 스코어링 파이프라인 공개 API

**Feature**: kb-53-llm-avoidance-scoring | 순수 도메인 서비스(IO 없음, Spring/ORM-free). 배치 잡이 소비.

## ScoringPromptFactory
```
class ScoringPromptFactory {
    fun build(foods: List<ScoringFood>, candidates: List<CandidateSubstance>): ScoringPrompt
}
data class ScoringPrompt(val prompt: String, val system: String?)
```
- **입력**: 청크 음식(≤10, 비어있지 않음), 후보 성분(카탈로그 전체).
- **출력 계약**: `prompt`/`system` 은 (1) 대상 음식 목록(한국어명), (2) 후보 성분 목록(코드+ko명), (3) "대표 레시피 기준", (4) **"포함되는 것만 응답"**, (5) 출력 JSON 스키마, (6) **probability 정수 1~100 강제**, (7) score 0/1/2 정의, (8) **음식명 9개 언어 번역 + 음식 설명(ko 생성 + 9개 번역, 각 공백 포함 ≤200자)** 지시를 포함한다.
- **불변식**: foods 비면 예외(호출 측이 빈 청크 미호출 — FR-001). 동일 입력 → 동일 프롬프트(결정적, 순서 고정).
- **경계**: LLM 타입(`LlmChatRequest`) 은 배치가 `ScoringPrompt → LlmChatRequest` 로 변환(research 는 `:infra:llm` 미의존).

## ScoringResponseParser
```
class ScoringResponseParser {
    fun parse(content: String, foods: List<ScoringFood>, candidates: List<CandidateSubstance>): ModelScoring
}
// ModelScoring: included: Map<Long, List<SubstanceJudgement>>, nameTranslations: Map<Long, Map<LanguageCode, String>>, descriptions: Map<Long, LocalizedText>
```
- **입력**: 단일 모델 raw content(JSON 기대), 매칭용 foods/candidates.
- **출력**: `ModelScoring`(foodId → 포함 판단 목록 **+ foodId → 음식명 번역 맵 + foodId → 설명 LocalizedText**). 응답에 없는 (음식,성분)은 담지 않음(=미포함).
- **처리 규칙(FR-009)**:
  - 미지 음식명 / 청크 외 음식 → 해당 항목 무시(skip).
  - 미지 성분코드 / score∉0..2 / probability∉1..100 / 비수치 → 해당 판단 무시(skip). 다른 유효 판단은 유지(부분 채택).
  - 동일 (음식,성분) 중복 → 첫 유효만 채택.
  - `nameTranslations` 미지 언어코드·`ko` 키·빈 값 → skip. 번역 객체 없음/깨짐 → 그 음식 번역 빈 맵(성분 파싱 유지).
  - `description` 미지언어·빈값 skip, **각 값 230자 초과 → 앞 230자 잘라내기**(목표 200). 없음/깨짐 → 빈 설명.
  - JSON 파싱 불가·빈 문자열 → 예외(`ScoringResponseParseException`) → 호출 측이 그 모델을 **실패로 격리**.
- **불변식**: 순수·결정적. 입력 content 를 변형하지 않음.

## FoodContentSelector (앙상블 아님 — 단일 모델 채택)
```
class FoodContentSelector {
    fun select(foodId: Long, orderedModelScorings: List<ModelScoring>): FoodContent  // (nameTranslations, description)
}
```
- **배치가 모델 응답을 우선순위(OPENAI→UPSTAGE→GEMINI) 순으로 정렬**해 넘긴다(research 는 `LlmModelId` 미의존 — 순서로만 우선순위 표현).
- 음식별로 **그 음식 텍스트(이름 번역+설명)를 비어있지 않게 제공한 첫 모델**의 것을 채택(이름·설명 동일 모델로 일관, 결정적). 아무도 없으면 빈 번역맵/빈 설명(ko 폴백).
- 텍스트는 수치가 아니므로 Consensus Ensemble 미적용(FR-015). 언어별 다수결은 후속.

## ConsensusEnsembleAggregator
```
class ConsensusEnsembleAggregator(policy: EnsemblePolicy = EnsemblePolicy.DEFAULT) {
    fun aggregate(
        foods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
        perModel: List<ModelScoring>,
    ): List<FoodScoringResult>
}
data class EnsemblePolicy(val scoreWeight: Double = 0.6, val floor: Int = 1)
```
- **입력**: **3개 모델 전부의** `ModelScoring`(정확히 3개). 3개 미만이면(일부 실패) **집계 안 함** — 배치가 청크를 미확정 처리(FR-007/008). 계약상 `perModel.size != 3` 이면 `IllegalArgumentException`(호출 측이 3개 확인 후 호출).
- **알고리즘**((음식, 후보성분) 마다):
  1. 각 모델에서 그 (음식,성분) 판단을 찾음 — 없으면 **누락 보정 score=0, probability=1**(research.md D4).
  2. `avgScore = mean(3 scores)`, `avgProbability = mean(3 probabilities)`.
  3. `base = 0.6·(avgScore/2) + 0.4·(avgProbability/100)`.
  4. `agreementFactor` = 3개 score 의 distinct 개수: 1→1.0, 2→0.9, 3→0.75(research.md D5).
  5. `inclusionConfidence = round(base·agreementFactor·100)` 를 **1..100 로 clamp**.
- **출력**: 음식별 `FoodScoringResult(SCORED, scores=후보 81 전부의 FoodInclusionScore, nameTranslations, description)`. 텍스트는 `FoodContentSelector.select` 결과(앙상블 아님).
- **골든(SC-003)**: foods=[비빔밥], 3모델이 EGG 를 score[2,1,2]·prob[90,70,80] 로 응답 → EGG `inclusionConfidence=74`, `agreementFactor=0.9`, `avgScore≈1.667`, `avgProbability=80`.
- **불변식**: 순수·결정적. 후보 순서대로 출력. 모든 confidence ∈ 1..100.

> **조립**: 배치가 `LlmFanoutResult` 에서 **successes.size==3 && failures 없음** 을 먼저 확인 → 3개 `ModelScoring` 을 우선순위 정렬 → `aggregate`(성분) + `FoodContentSelector.select`(이름·설명)를 합쳐 `FoodScoringResult(SCORED)` 구성. 하나라도 실패면 aggregate 미호출, 실패 모델 로깅 + 청크 미확정(음식 FAILED).

## 테스트 계약(헌법 I — 실패 선작성)
- Prompt: 스키마·"포함된 것만"·1~100 강제·번역 9개 언어·설명(ko+번역,≤200자) 지시 포함, 빈 청크 예외, 결정성.
- Parser: 정상 부분응답 파싱 / 미지코드·범위이탈·중복·미지음식 skip / 번역·설명 미지언어·ko·빈값 skip·깨짐→빈 / 설명 230자 초과 잘라내기 / 형식오류·빈 → 예외.
- Aggregator: §5 골든 74 / agreement 1.0·0.9·0.75 / **perModel.size!=3 → 예외** / 전부누락→confidence 1 / clamp.
- FoodContentSelector: 우선순위 첫 비어있지 않은 모델 채택(이름·설명 동일 모델) / 전부 없음→빈 / 결정성.
- 배치 조립: successes==3 확정 / 1~2개 실패 → 미확정 + 실패 로깅 / 전멸 → 미확정.
