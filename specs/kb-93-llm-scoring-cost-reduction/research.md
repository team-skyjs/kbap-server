# Research: LLM 스코어링 호출 비용 절감 (KB-93)

**Date**: 2026-07-07 | **Plan**: [plan.md](plan.md)

KB-53 실측에서 출력 토큰의 80%+ 가 음식 설명 텍스트였고, key-value JSON(코드·언어 키 반복)·한국어 라벨·장문 지시문이 나머지 오버헤드의 주범이었다. 아래 결정들은 스코어링 산출 의미(FR-003)·청크당 모델별 1회 호출(FR-006)·3모델 전량 취합 확정(FR-008)을 불변 조건으로 두고 내렸다.

## R1. 압축 응답 스키마와 음식 커버리지 신호

**Decision**: 응답은 단일 JSON 객체로 받는다 —

```json
{"c":[0,1,2,3,4,5,6,7,8,9],"r":[[0,12,2,90],[0,5,1,40],[3,7,2,85]]}
```

- `r`: 포함 판단 배열. 각 항목은 `[음식인덱스, 성분인덱스, score, probability]`(FR-001).
- `c`: 모델이 판단을 마친 음식 인덱스 목록(커버리지 attest). 포함 성분이 0개인 음식도 `c` 에는 있어야 한다.
- 파서의 `coveredFoodIds` = (`c` 의 유효 인덱스) ∪ (`r` 유효 항목의 음식 인덱스) — 관대한 합집합.

**Rationale**: KB-53 확정 게이트는 "모델별 음식 전량 커버"인데, 압축 포맷은 미포함 쌍을 생략하므로(스펙 Clarification) 포함 성분이 없는 음식은 `r` 에 등장하지 않는다 — 커버리지를 판단 배열에서 유추할 수 없어 별도 attest(`c`)가 필요하다. `c` 는 청크 10개 기준 최대 ~25 토큰으로 비용 영향이 무시할 수준이며, KB-53 의 `"included": []` 강제(음식당 entry 1개 필수)와 의미상 동형이다. 합집합으로 두는 이유: 모델이 `c` 에 빠뜨렸지만 유효 판단을 낸 음식을 커버 미달로 처리하면 불필요한 청크 미확정(재조사 비용)이 늘어난다 — 유효 판단의 존재 자체가 그 음식을 판단했다는 더 강한 증거다.

**Alternatives considered**:
- **음식 인덱스 키 객체** `{"0":[[12,2,90]],"1":[]}` — 커버리지가 키 존재로 자연 표현되지만, 스펙 FR-001 이 명시한 flat 4-원소 배열 형태에서 벗어나고, 빈 배열 음식 10개의 키·괄호 오버헤드가 `c` 배열보다 크다. 기각.
- **커버리지 신호 생략(암묵 전량 커버 간주)** — 모델이 뒷부분 음식을 빠뜨려도(장문 응답 절단·태만) 감지 불가 → KB-53 커버리지 게이트 의미 훼손(FR-003 위반). 기각.
- **flat 배열 단독 + "모든 음식에 최소 1개 판단 강제"** — 실제로 아무 후보도 포함되지 않는 음식(과일 등)에 거짓 판단을 유도해 정확도를 해친다. 기각.

## R2. 이름 번역 압축 포맷 (Gemini 전용)

**Decision**: 번역 담당 모델(Gemini) 응답에만 최상위 `t` 필드를 추가한다 —

```json
{"c":[...],"r":[...],"t":[[0,["炒饭","Fried rice","チャーハン","炒飯","Cơm chiên","Nasi goreng","ข้าวผัด","Жареный рис","Arroz frito"]]]}
```

- `t` 항목은 `[음식인덱스, [9개 번역 배열]]`. 번역 배열은 **고정 언어 순서**(프롬프트에 1회 선언: `zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es` — `LanguageCode.entries` 에서 `KO` 제외 순서)로 언어 키를 생략한다.
- 파서는 위치 인덱스로 언어를 복원한다. 배열 길이 부족 시 존재하는 위치까지만 채택(초과분 스킵), blank 항목은 그 언어 누락으로 처리 — best-effort(FR-008, 확정 게이트 무관).

**Rationale**: KB-53 은 음식 10개 × 9개 언어 키(`"zh-Hans":` 등) 반복으로 언어 키만 ~90회 출력했다. 위치 기반 배열은 키 토큰을 전부 제거해 번역 출력에서 키 오버헤드를 0 으로 만든다. 고정 순서는 `LanguageCode` enum 선언 순서라는 단일 출처를 재사용해 프롬프트·파서 간 드리프트가 없다.

**Alternatives considered**:
- **KB-53 `nameTranslations` 객체 유지** — 언어 키 반복 오버헤드 잔존, Gemini 1개 모델이라 절대량은 작지만 압축 원칙 비일관. 기각.
- **번역만 별도 호출 분리** — 청크당 모델별 1회 호출(FR-006) 위반. 기각.

## R3. 모델별 프롬프트 분기 seam 의 위치와 형태

**Decision**: `LlmFanoutClient` 에 모델별 요청 함수 오버로드를 추가한다 —

```kotlin
fun generate(requestFor: (LlmModelId) -> LlmChatRequest): LlmFanoutResult
```

기존 `generate(request)` 는 `generate { request }` 로 위임(또는 유지). `:core:research` 의 `ScoringPromptFactory` 는 `build(foods, candidates, includeNameTranslations: Boolean)` 로 역할 변형만 제공하고, **어느 모델이 번역 담당인지는 `:app:batch` 잡만 안다**(GEMINI → `includeNameTranslations = true`, 그 외 false — 프롬프트 2종을 청크당 1회씩 구성해 재사용).

**Rationale**: fan-out 은 활성 caller 목록을 내부에 소유하므로 호출자가 caller 별 요청을 지정하려면 `LlmModelId` 기반 seam 이 필요하다. 함수형 seam 은 (a) 모든 활성 caller 에 대해 전역적(누락 키 불가 — Map 방식의 부분 정의 문제 없음), (b) 벤더 중립(`:infra:llm` 은 "모델마다 요청이 다를 수 있다"만 알고 번역/스코어링 의미 미인지), (c) 호출 수 불변(모델별 1회, FR-006). `:core:research` 에 불리언 변형만 두는 것은 KB-53 격리 원칙(모델·벤더 미인지, primitive 격리) 유지다.

**Alternatives considered**:
- **`generate(requests: Map<LlmModelId, LlmChatRequest>)`** — 활성 caller 와 Map 키 불일치(누락/잉여) 처리 규칙이 추가로 필요. 함수형이 전역성을 타입으로 보장. 기각.
- **fan-out 을 두 그룹(번역/비번역)으로 2회 호출** — 호출 수는 유지되나 fan-out 의 "3모델 전량 결과" 단일 취합 지점이 흩어져 확정 게이트 조립이 복잡해진다. 기각.
- **`ScoringPromptFactory` 가 `LlmModelId` 를 받아 분기** — `:core:research` 가 `:infra:llm` 타입을 알게 돼 의존 방향 위반(원칙 III). 기각.

## R4. 출력 토큰 상한·추론 노력 옵션의 벤더별 매핑

**Decision**: `LlmModelProperties.ModelProps` 에 `maxOutputTokens: Int?` 와 `reasoningEffort: String?` 을 추가하고 `LlmConfiguration` 에서 벤더별로 배선한다:

| 모델 | 출력 상한 매핑 | 추론 노력 매핑 |
|------|--------------|--------------|
| OpenAI (gpt-5-nano) | `OpenAiChatOptions.maxCompletionTokens` (gpt-5 계열은 `max_tokens` 미지원 — `max_completion_tokens` 필수) | `OpenAiChatOptions.reasoningEffort = "minimal"` |
| Upstage (solar-mini, OpenAI 호환) | `OpenAiChatOptions.maxTokens` (표준 OpenAI 호환 파라미터) | 미설정(추론 모델 아님 — 프로퍼티 미지정 시 옵션 미포함) |
| Gemini (gemini-2.5-flash-lite) | `GoogleGenAiChatOptions.maxOutputTokens` | 미설정(flash-lite 는 thinking 기본 off — 추가 옵션 불요) |

기본값은 `app/batch/src/main/resources/application.yml` 에 두고(실행 인자로 덮어쓰기 가능 — 기존 패턴), 미설정(null)이면 옵션을 아예 싣지 않는다(KB-49 boot-safety 유지). gpt-5-nano 모델·단가($0.05/$0.40 per 1M)는 **KB-53 배포 시점에 이미 yml 에 반영돼 있다** — 이 기능에서는 검증만 하고, 옵션(추론 최소·출력 상한)을 추가한다.

상한 값 산정(연구 추정, 스모크로 보정): 스코어링 전용 응답은 `c`(≤25) + `r`(포함쌍 최대 810 × ~9토큰, 현실 분포 ~80쌍 × ~9 ≈ 720) 기준 **2,048** 를 기본으로, 번역 포함(Gemini) 응답은 `t`(10음식 × 9언어 × ~5토큰 ≈ 450+구조) 를 더해 **4,096** 을 기본으로 둔다 — 정상 응답을 절단하지 않으면서 폭주(설명 생성·잡담)를 차단하는 여유율 ~2×.

**Rationale**: 출력 상한은 "지시 무시하고 장문 출력" 시나리오의 비용 상한 장치다(스펙 US3). 절단되면 유효 JSON 이 아니게 되고 → 파싱 실패 → 모델 실패 → 청크 미확정 + 로깅으로 이미 정의된 실패 경로(Edge Case)에 합류한다. reasoning effort minimal 은 gpt-5 계열의 추론 토큰(출력 토큰으로 과금) 낭비를 차단한다 — 이 과제는 다단 추론이 아니라 지식 조회형 판정이다.

**Alternatives considered**:
- **전 벤더 단일 `maxTokens` 프로퍼티명 없이 벤더별 프로퍼티** — 설정 표면이 벤더 파라미터명에 결합돼 모델 교체 시 yml 스키마가 흔들린다. 중립 명칭 `max-output-tokens` 1개 + 배선에서 벤더 매핑. 채택안이 이것이다.
- **Gemini `thinkingBudget = 0` 명시** — flash-lite 는 기본 off 라 불필요 옵션·미지원 모델 교체 시 오류 위험만 추가. 필요해지면(모델 상향 시) 후속. 기각.
- **응답 절단 시 부분 파싱 구제**(잘린 JSON 복구) — 파서 복잡도 대비 이득 없음(KB-53 실패 경로가 이미 재조사를 보장). 기각.

## R5. 프리픽스 캐싱 정렬 방식

**Decision**: 요청 토큰 스트림에서 **청크마다 변하지 않는 부분을 전부 앞으로** 모은다 — system 메시지에 [지시문 + 응답 스키마 + 고정 언어 순서(번역 변형만) + 후보 81종 인덱스 목록]을 두고, user 메시지에는 [음식 청크 인덱스 목록]만 둔다. 벤더의 명시적 캐시 API(cache key·cached_content)는 쓰지 않는다.

**Rationale**: OpenAI 는 1,024 토큰 이상 프리픽스가 동일하면 자동 캐싱(입력 단가 할인), Gemini 2.5 도 implicit caching 을 지원한다 — 둘 다 "동일 프리픽스" 조건이라 배치 순서만 맞추면 별도 통합 없이 할인이 적용된다. 후보 81종은 청크와 무관한 상수이므로 system 으로 승격하는 것이 캐싱과 의미(판단 기준의 일부) 양쪽에 맞다. 스펙 전제대로 캐싱은 추가 마진이며 미적용이어도 R1~R4 만으로 목표를 만족해야 한다.

**Alternatives considered**:
- **명시적 캐시 API 사용**(Gemini cachedContent 등) — 벤더별 통합·TTL 관리 비용이 들고, `:infra:llm` 의 벤더 중립 표면을 깨뜨린다. 자동 캐싱으로 충분. 기각.
- **후보 목록을 user 메시지 앞부분에 유지** — system 이 짧으면 1,024 토큰 문턱을 못 넘을 수 있고, user 선두의 동일성 유지가 더 취약(음식 목록과 한 메시지). system 승격이 우월. 기각.

## R6. KB-53 파서 동등성(SC-005) 검증 전략

**Decision**: 구 파서를 병행 유지하지 않고 **제자리 교체 + 골든 동등성 테스트**로 검증한다. 동일한 판단 집합(예: KB-53 골든 픽스처의 (음식,성분,score,probability) 조합)을 (a) KB-53 key-value JSON 픽스처가 산출했던 기대 `ModelScoring`(테스트에 고정된 기대값)과 (b) 같은 판단을 압축 배열로 표현한 픽스처의 신규 파서 산출로 비교해 `included`·`coveredFoodIds` 가 동일함을 단언한다. 이탈 규칙(범위이탈·형식오류·인덱스이탈·중복 첫-채택·커버리지 미달 → 미취합)도 KB-53 테스트 케이스를 압축 포맷으로 1:1 이식한다.

**Rationale**: 구 포맷은 프롬프트 교체와 동시에 어떤 모델도 반환하지 않는 사장 계약이다 — 병행 유지는 죽은 코드만 남긴다. 동등성의 본질은 "파서 규칙의 의미 보존"이므로, 규칙별 케이스를 포맷만 바꿔 이식하면 충분하고 회귀도 잡힌다.

**Alternatives considered**:
- **구 파서 존치 + 런타임 이중 파싱 비교** — 운영 복잡도·죽은 코드 유지 비용 대비 이득 없음(응답 자체가 한 포맷으로만 온다). 기각.
- **파서 신규 클래스 추가(`CompressedScoringResponseParser`)** — 소비자(`AvoidanceScoringJob`)가 1곳뿐이고 구 클래스가 사장되므로 이름 유지·제자리 교체가 더 단순. 기각.

## R7. 비용 실측 로그 (FR-011/SC-001)

**Decision**: 기존 `SpringAiModelCaller.logTokenUsage`(모델·promptTokens·completionTokens·costUsd·costKrw, KB-53 도입)를 그대로 사용한다. 신규 코드 없음 — 검증은 스모크 실행 시 이 로그로 청크당 모델별 KRW 를 읽는다. 압축·역할 분리로 응답이 짧아지므로 `call-timeout: 180s`(설명 장문 대응용) 는 스모크 실측 후 하향을 검토한다(선택 — 목표와 무관).

**Rationale**: 호출 = 청크 × 모델 단위이므로 기존 per-call 로그가 판정 단위(스펙 Clarification: 청크당 모델별 ₩1)와 정확히 일치한다. 이미 USD/KRW(1,500 고정)·단가(yml)를 반영한다.

**Alternatives considered**:
- **청크 단위 집계 로그 추가**(3모델 합산) — 판정 단위가 모델별이라 불필요. 기각.

## 비용 절감 추정 (목표 타당성)

| 항목 | KB-53 (실측 기반) | KB-93 (추정) |
|------|------------------|-------------|
| 출력: 음식 설명(ko+9언어 × 10음식) | ~10,000+ 토큰 (출력의 80%+) | **0** (제거) |
| 출력: 이름 번역 | 3모델 × (10음식 × 9언어 키+값) | Gemini 1모델 × 위치 배열(키 0) ≈ ~500 토큰 |
| 출력: 판단 | key-value(`"code"`·`"score"`·`"probability"` 키 반복) | flat 배열 ≈ ~700 토큰 |
| 입력: 후보 라벨·장문 지시문 | 코드+한국어 라벨 81줄 + 장문 규칙 | 코드 전용 인덱스 목록 + 축약 지시문 |

추정 비용(청크당): OpenAI gpt-5-nano — 입력 ~1.5k × $0.05/1M + 출력 ~1k × $0.40/1M ≈ $0.0005 ≈ **₩0.7**; Gemini flash-lite — 출력 ~1.5k × $0.40/1M + 입력 ≈ **₩1 미만**; Upstage solar-mini ≈ **₩0.5 미만**. 여유율이 크지 않은 Gemini(번역 담당)가 임계 — 스모크 실측으로 확인하고, 초과 시 지시문 추가 축약이 1순위 레버다.

## 잔여 NEEDS CLARIFICATION

없음.
