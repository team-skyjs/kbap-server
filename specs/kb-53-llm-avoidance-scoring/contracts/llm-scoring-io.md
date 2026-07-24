# Contract: LLM 스코어링 프롬프트·응답 I/O

**Feature**: kb-53-llm-avoidance-scoring | `ScoringPromptFactory` 가 생성하는 지시와 모델이 지켜야 할 응답 스키마.

## 프롬프트 지시(system + user 요지)
- 역할: "음식 대표 레시피 기준으로, 주어진 후보 기피성분의 **포함 가능성**을 판단하는 분석기."
- 대상: 음식 목록(한국어명) N(≤10) + 후보 성분 목록(`code` + 한국어명) 81.
- 기준: 특정 식당이 아닌 **일반적으로 알려진 대표 조리 방식**(문서 §3).
- 출력 규칙:
  - **포함된다고 판단한 (음식, 성분)만** 반환(미포함은 생략).
  - 각 항목: `code`(후보 코드 그대로), `score`(0=낮음/1=가능성 있음/2=높음), `probability`(**정수 1~100**).
  - 후보에 없는 성분코드·목록에 없는 음식은 반환 금지. 오직 JSON 만 출력.
  - **음식명 번역**: 각 음식에 대해 대상 9개 언어(`zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es`) 번역명을 `nameTranslations` 에 담는다. `ko` 키는 넣지 않는다(원문). 모르는 언어는 키 생략.
  - **음식 설명**: 각 음식에 대해 `description.ko`(한국어 설명 생성)와 `description.translations`(9개 언어 번역)를 담는다. **각 값은 공백 포함 200자를 목표**로 한다(최대 230자).

## 응답 JSON 스키마
```json
{
  "results": [
    {
      "food": "<주어진 한국어 음식명 그대로>",
      "included": [
        { "code": "EGG", "score": 2, "probability": 90 }
      ],
      "nameTranslations": {
        "en": "Bibimbap", "ja": "ビビンバ", "zh-Hans": "拌饭",
        "zh-Hant": "拌飯", "vi": "Cơm trộn", "id": "Bibimbap",
        "th": "บิบิมบับ", "ru": "Пибимпаб", "es": "Bibimbap"
      },
      "description": {
        "ko": "밥에 나물·고기·계란·고추장을 얹어 비벼 먹는 한국의 대표 혼합밥 요리.",
        "translations": {
          "en": "A Korean mixed-rice dish ...", "ja": "...", "zh-Hans": "...",
          "zh-Hant": "...", "vi": "...", "id": "...", "th": "...", "ru": "...", "es": "..."
        }
      }
    }
  ]
}
```

### 필드 계약
| 필드 | 타입 | 제약 |
|------|------|------|
| `results[].food` | string | 프롬프트의 음식명과 정확 일치(역매핑 키) |
| `included[].code` | string | 후보 코드 집합 내 |
| `included[].score` | int | 0..2 |
| `included[].probability` | int | 1..100 (프롬프트 강제) |
| `results[].nameTranslations` | object | 키 = 대상 9개 언어코드 부분집합(`ko` 제외), 값 = 번역명. `LocalizedText.translations`·`food.name_translations` 동형 |
| `results[].description.ko` | string | 한국어 설명(생성), 공백 포함 목표 200·최대 230자 |
| `results[].description.translations` | object | 키 = 대상 9개 언어(`ko` 제외), 값 = 설명 번역, 각 공백 포함 목표 200·최대 230자. `food.description_translations` 동형 |

## 파서 방어 규칙(FR-009) — [scoring-pipeline.md](./scoring-pipeline.md) `ScoringResponseParser`
- 미지 `food` / 미지 `code` / `score`∉0..2 / `probability`∉1..100 / 비수치 → 그 항목 skip(부분 채택).
- 동일 (food, code) 중복 → 첫 유효만.
- `nameTranslations` 의 미지 언어코드(대상 9개 밖)·`ko` 키·빈 문자열 값 → 그 키 skip. 없거나 깨진 번역 객체 → 그 음식 번역 빈 맵(성분 파싱은 유지).
- `description.ko` 없음/공백 → 그 음식 설명 빈 값. `description.translations` 미지언어·빈값 skip. **각 값 공백 포함 230자 초과 → 앞 230자로 잘라내기**(목표 200, 200~230 허용).
- JSON 파싱 불가·빈 content → 그 모델 실패 격리(`ScoringResponseParseException`).
- 코드펜스(```json …```) 래핑 허용(전처리로 스트립).

## `:infra:llm` 매핑(배치 잡)
- `ScoringPrompt(prompt, system)` → `LlmChatRequest(prompt = prompt, system = system)`.
- `LlmFanoutClient.generate(request)` → `LlmFanoutResult`:
  - `successes: List<LlmChatResult(modelId, content)>` → 각 `content` 를 `ScoringResponseParser.parse` → `ModelScoring`.
  - `failures` + 파싱 실패 → 해당 모델 **별도 로깅(modelId + 사유)** 후 제외.
  - **확정 조건: `successes` 파싱 성공 == 3(전 모델)**. 하나라도 실패/파싱불가 → 청크 **미확정**(음식 전부 `FAILED`, 재조사) — 부분 집계 금지.
- **호출 단위**: 청크 1개 = `generate` 1회(= 3개 모델 각 1회 동시 병렬). 청크 내 음식은 한 프롬프트에 함께.
