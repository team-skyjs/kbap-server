# Phase 0 Research: 기피성분 포함 신뢰도 LLM 스코어링

**Feature**: kb-53-llm-avoidance-scoring | **Date**: 2026-07-06

스펙 Clarifications 로 대부분 확정됨(청크 10·Consensus Ensemble·inclusionConfidence 1~100·포함된 것만 응답). 여기서는 plan 단계에 남은 **구현 설계 결정**을 확정한다.

---

## D1. 스코어링 도메인 로직의 모듈 배치

- **Decision**: 프롬프트 구성·응답 파싱·앙상블 종합을 **`:core:research`** 순수 도메인 서비스로 둔다. `:infra:llm` 은 벤더 중립 전송 계층 그대로. 조율(대기열→LLM→종합)은 `:app:batch` 잡.
- **Rationale**: ADR-0004 가 research 를 "미스 메뉴 조사·종합 파이프라인, 배치 전용, **종합 판단은 순수 도메인 서비스(IO 없음)**"로 규정. 앙상블 종합이 정확히 그 "종합 판단"이다. 순수 서비스라 실 네트워크 없이 단위검증(헌법 I). `:infra:llm` 에 도메인(음식·성분)을 유입시키지 않아 벤더 중립 유지(KB-49 제약).
- **Alternatives considered**:
  - *배치 잡 안에 로직 구현* — ADR-0004 가 명시 비채택(도메인 로직이 실행 앱에 묻혀 부팅 없이 테스트 불가, 헌법 II 위반).
  - *`:infra:llm` 에 스코어링 파서/프롬프트 추가* — infra 가 도메인 어휘(성분·음식·score)를 알게 돼 벤더 중립·계층 방향 훼손. 비채택.
  - *`:application:batch` 신설해 조합* — 정석(ADR-0004 §4)이나 유스케이스 1개엔 과함(§6 승격 트리거 미도달). KB-49/ADR-0010 배치-직접 선례를 따르고 승격은 트리거 시 후속.

## D2. research 의 도메인 격리 방식(원칙 II)

- **Decision**: `:core:research` 는 `:core:food`·`:core:avoidance` 타입을 import 하지 않는다. 자체 primitive 값타입 `ScoringFood(foodId: Long, koreanName: String)`·`CandidateSubstance(code: String, koreanLabel: String)` 으로만 입력받는다. 배치 잡이 `Food`·`AvoidanceSubstance` → 이 값타입으로 매핑한다.
- **Rationale**: 컨텍스트 간 전체 객체 비참조(원칙 II) — 코드/스냅샷 값으로만. research 를 food/avoidance 변경으로부터 격리하고 순수 단위테스트를 쉽게 한다.
- **Alternatives**: research 가 food/avoidance 를 `implementation` 의존 → 컨텍스트 결합, 원칙 II 위반. 비채택.

## D3. 프롬프트/응답 포맷 — "포함된 것만" 구조화 응답

- **Decision**: 한 청크(음식 ≤10) + 후보 성분(카탈로그 ko 명) 전체를 1개 프롬프트로 제시하고, 모델은 **JSON** 으로 **포함으로 판단한 (음식, 성분)만** 반환한다. probability 는 프롬프트로 **정수 1~100 강제**, score 는 0/1/2. 음식은 **한국어 음식명 문자열**을 키로 매칭(응답→foodId 역매핑). 시스템 메시지에 역할·출력 스키마·"대표 레시피 기준"·"포함되는 것만"·범위 규칙을 명시.
- **응답 JSON 스키마(계약)**:
  ```json
  { "results": [
      { "food": "비빔밥",
        "included": [ { "code": "EGG", "score": 2, "probability": 90 } ] }
  ] }
  ```
- **Rationale**: JSON 은 `jackson-module-kotlin`(기존 의존)으로 견고 파싱. "포함된 것만" 반환은 응답 크기·토큰 절약(사용자 지시). 음식명 키는 기존 `FoodRepository.findByKoreanName` 및 [[menu-scan-menu-name-db-mapping]] 패턴과 정합.
- **Alternatives**:
  - *성분마다 전 음식×전 성분(81) 강제 응답* — 응답 폭증·토큰 낭비. 비채택(포함된 것만).
  - *Spring AI structured output(BeanOutputConverter)* — 벤더별 지원 편차·`:infra:llm` 에 스키마 유입. MVP 는 프롬프트 지시 + 방어적 JSON 파싱으로 충분. 후속 고려.
  - *foodId 를 프롬프트에 노출해 키로 사용* — 모델이 임의 id 를 환각할 위험. 음식명 키가 자연스럽고 검증 쉬움.

## D4. 누락(미포함) 처리와 앙상블 입력 정규화

- **Decision**: 모델이 (음식, 성분)을 응답에서 **누락 = 그 모델이 "미포함"으로 판단**한 것으로 해석해, 앙상블 입력에서 **score=0, probability=1** 로 정규화한다. 따라서 각 성공 모델은 (음식, 후보 81성분) 전부에 대해 (score, probability)를 갖는다(포함 응답분 + 누락 보정분).
- **Rationale**: 사용자 지시("포함되는 것만 응답")와 문서 §4 공식(모델당 성분별 score/probability 필요)을 화해시킨다. 부수효과로 **후보 81종 전부가 최종값을 얻어**(대부분 누락→저confidence) DoD "81종 전 성분 산출"을 자연 충족. 전부 누락된 성분 → score[0,0,0]·prob[1,1,1] → base=0.6·0 + 0.4·0.01=0.004, agreement=1.0, round(0.4)=0 → **clamp 1**(SAFE 등가).
- **Alternatives**: *응답한 모델만으로 집계(누락=무의견)* — 1개 모델만 flag 한 성분이 그 1개로만 산출돼 과신, agreement 무의미. 문서의 3원소 배열 전제와도 불일치. 비채택.

## D5. Consensus Ensemble 공식 (3개 모델 모두 필요)

- **Decision**: **3개 모델 결과가 모두 취합됐을 때만** 집계한다(D10 — 부분 집계 금지). 집계는 항상 n=3:
  - `avg_score = mean(3 scores)` (누락=0), `avg_probability = mean(3 probabilities)` (누락=1).
  - `base = 0.6·(avg_score/2) + 0.4·(avg_probability/100)`.
  - `agreement_factor`: **3개 score 의 distinct 개수** — 1→1.0, 2→0.9, 3→0.75.
  - `final = base · agreement_factor`; `inclusionConfidence = round(final·100)`; **clamp 1..100**(공식상 0 방어).
- **Rationale**: 문서 §4·표(모두동일=1.0, 2동일=0.9, 모두상이=0.75)와 정확히 일치. 문서 §5 골든(`score[2,1,2]`·`prob[90,70,80]`): avg_score=1.667→/2=0.833, avg_prob=80→0.8, base=0.6·0.833+0.4·0.8=0.820, distinct{2,1}=2→0.9, final=0.738, round(73.8)=**74** ✅.
- **Alternatives**: *가용 n∈{1,2} 로 일반화해 부분 완결* — 초기 검토안이었으나 사용자 지시(2026-07-06)로 **3개 모두 필요**로 확정, 폐기. distinct 규칙은 n=3 전제라 단순화.
- **정책 상수**: `α=0.6`, agreement(1.0/0.9/0.75), clamp 하한 1 — research 모듈 상수로 문서화(문서 §4-2 Note: MVP 정책값, calibration 여지).

## D6. 음식 공급 seam(대기열)

- **Decision**: `:core:food` 에 읽기 port `FoodScoringSource.nextChunk(size: Int): List<Food>`(또는 `findScoringTargets(limit)`)를 신설. **초기 구현**은 `:infra:persistence` 어댑터가 active `food` 를 읽어 공급(스키마·마이그레이션 무변경). **전용 대기열 테이블·재조사 상태·중복제거·재시도는 후속**(ADR-0004 후속 리스크 ③, KB-54 또는 별도 태스크).
- **Rationale**: 스펙 clarify — "음식 공급은 seam, 대기열 테이블은 후속". 기존 `food`·`food_avoidance_substance` 테이블이 이미 있어 실데이터로 구동 가능. seam 이라 테스트는 페이크로 대체.
- **Alternatives**: *지금 대기열 테이블 신설* — 재조사 트리거·상태관리 설계가 독립 규모, KB-53 핵심(프롬프트/응답/종합)과 무관. 비채택(연기).
- **Open(후속)**: "조사 필요 음식" 판별 기준(매핑 부재 vs 재조사 플래그) — 초기엔 단순 전체/미매핑 필터, 정교화는 후속.

## D7. `:infra:llm` 변경 여부

- **Decision**: `:infra:llm` **무변경**. 기존 `LlmChatRequest(prompt, system)` + `LlmFanoutClient.generate → LlmFanoutResult(successes: List<LlmChatResult(modelId, content)>, failures)` 를 그대로 사용. 스코어링은 prompt 문자열에 포맷 지시를 담고 raw content 를 research 파서가 처리.
- **Rationale**: 벤더 중립·최소 변경. 결정성(temperature 등) 튜닝 노브는 필요 시 후속으로 `LlmChatRequest` 확장(현재 범위 밖 — 다수 모델·앙상블이 비결정성 완화).
- **Alternatives**: *LlmChatRequest 에 temperature/responseFormat 추가* — 지금 불필요, YAGNI. 후속.

## D8. 결정성·테스트 전략

- **Decision**: 결정성이 필요한 부분(앙상블 공식)은 순수 함수라 골든 테스트로 고정(문서 §5=74). LLM 자체 비결정성은 다수 모델+집계로 완화(문서 §7-1). 실키 3모델 스모크는 `@Disabled`+수동(KB-49 패턴 계승).
- **Rationale**: 헌법 I — 네트워크 없는 Red→Green. 파서/프롬프트/앙상블 전부 순수라 페이크 불요, 잡만 페이크 `LlmFanoutClient`.

## D9. 음식명 번역을 같은 호출에서 수신

- **Decision**: 스코어링 프롬프트에 **음식명(ko 원문)의 대상 9개 언어(헌법 V) 번역**을 함께 요청하고, 응답 JSON `results[].nameTranslations`(`{lang_code: 번역명}`, `ko` 키 없음)로 받아 파싱한다. 번역은 **수치가 아니라 앙상블 대상이 아니며**, `FoodContentSelector` 가 우선순위(OPENAI→UPSTAGE→GEMINI) 순 **첫 비어있지 않은 모델**을 채택한다. 산출물 `FoodScoringResult.nameTranslations`(`Map<LanguageCode, String>`)는 `LocalizedText.translations`·`food.name_translations`(KB-48) 와 동형 — KB-54 가 변환 없이 저장.
- **Rationale**: 이미 음식을 LLM 에 보내므로 같은 호출에서 번역을 얻으면 호출·비용 절감(사용자 지시). 기존 JSON 칼럼 구조 재사용으로 하류 저장 무마찰. 헌법 V(콘텐츠 9개 언어 사전번역)와 정합. research 는 kernel `LanguageCode`/`LocalizedText` 만 사용(food 미의존, 원칙 II 유지).
- **선정 규칙**: 언어별 다수결·투표가 아니라 **모델 우선순위 단일 채택**(MVP 단순·결정적). research 는 `LlmModelId` 를 모른 채 **정렬된 순서**로만 우선순위를 받는다(배치가 정렬). 채택 모델이 빠뜨린 언어는 키 생략 → 조회 시 ko 폴백(헌법 V). 이름·설명은 **같은 채택 모델**에서 뽑아 일관.
- **범위**: 음식명 번역 **+ 음식 설명**(2026-07-06 추가). 언어 수는 헌법 V **9개**(사용자 "10개국"은 +ko 포함 총 10 해석).
- **Alternatives**:
  - *번역/설명을 별도 LLM 호출/별도 태스크(T3)로 분리* — 호출 2배·비용↑. 사용자 지시(같은 호출)와 배치. 비채택.
  - *번역도 앙상블(언어별 다수결)* — 텍스트 종합은 정렬·동치 판단이 모호, MVP 과함. 단일 채택으로 충분(후속 다수결).

## D10. 음식 설명 생성(한국어 + 9개 언어) — 2026-07-06 추가

- **Decision**: 같은 호출에서 **음식 설명**을 함께 생성한다 — 음식별 **한국어 설명(LLM 생성)** + 9개 언어 번역, 각 **공백 포함 200자 목표·하드캡 230자**(200~230 허용, 230 초과분 앞 230자로 잘라내기). 응답 JSON `results[].description = { "ko": "...", "translations": {…9개…} }`, 산출물 `LocalizedText`(korean + translations). `food.description`·`food.description_translations` 와 동형. `FoodContentSelector` 가 이름 번역과 함께 우선순위 단일 채택.
- **Rationale**: 사용자 지시(설명도 저장). 음식명과 달리 **ko 원문도 LLM 이 생성**(대기열 음식엔 설명 부재). 하드캡 230 은 기존 `FoodContent.MAX_DESCRIPTION_LENGTH=255` 이내라 저장 호환. 230 초과 잘라내기 — 파서/검증에서 강제.
- **Alternatives**: *설명 255자 그대로* — 사용자가 200자 명시. *설명 앙상블* — 텍스트라 부적합, 단일 채택.

## D11. 모델 완결성 — 3개 모두 필요 + 실패 로깅 (2026-07-06 변경)

- **Decision**: 청크 확정은 **3개 모델 결과 모두 취합 시에만**. 일부 실패(예외·타임아웃·API 다운·파싱 불가)는 **모델별 별도 로깅**(modelId + 사유, KB-49 `LlmModelFailure` 재사용)하고 그 청크는 **미확정**(재조사)으로 남긴다 — 부분 집계·부분 산출물 하류 유입 금지.
- **Rationale**: 사용자 지시(2026-07-06) — 안전 직결 데이터라 부분 결과 불신. 기존 clarify "≥1 성공 완결"·KB-53 Jira DoD "일부 실패해도 완결"을 **대체**(⚠️ Jira DoD 갱신 요망). fan-out(KB-49)은 실패를 격리·수집하므로 잡이 `failures` 비어있음 + successes.size==3 을 확정 조건으로 검사.
- **Alternatives**: *부분 완결(≥1)* — 초기안, 사용자 지시로 폐기. *2개 이상이면 확정* — 사용자는 "3개 취합" 명시, 비채택.

---

## 미해결(플랜 밖·후속)
- 전용 조사 대기열 테이블·재조사·중복제거·재시도(D6 후속).
- `:application:batch` 승격(ADR-0004 §6 트리거 도달 시).
- LLM 호출 결정성 노브(temperature)·structured-output 전환(D3/D7 후속).
- 결과 영속·KB-9 위험도 연동(KB-54/T5).
