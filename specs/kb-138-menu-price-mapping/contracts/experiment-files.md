# File Contracts: KB-138 실험 파일 계약

API 엔드포인트 없음(스파이크·web 미노출). 계약은 파일 3종 + LLM 프롬프트/응답 형식이다.

## 1. `experiment/samples.json` (입력 manifest)

```json
[
  {
    "id": "s01-printed-clean",
    "imageUrl": "https://<bucket>.s3.amazonaws.com/menu/s01.jpg?X-Amz-...",
    "conditions": ["printed", "bilingual"],
    "label": [
      { "name": "김치찌개", "price": 8000 },
      { "name": "공기밥", "price": 1000 },
      { "name": "오늘의 반찬", "price": null }
    ]
  }
]
```

- `imageUrl` 은 http(s) URL 만 허용(로컬 파일 경로가 오면 하네스가 즉시 거부 — FR-001 가드).
- `label` 은 사람이 사진을 보고 수기 작성한 정답(FR-004).

## 2. LLM 호출 계약 (프롬프트 → 응답)

**요청**: user 메시지 = 지시 텍스트 + `image_url`(샘플의 `imageUrl` 그대로). 지시 요지:

> 메뉴판 사진에서 모든 메뉴와 가격을 추출해 JSON 배열로만 답하라.
> 형식: `[{"name": "메뉴명", "price": 8000}]`. 가격 미표기는 `"price": null`.
> 메뉴명은 사진 표기 그대로(한국어 우선). 메뉴가 아닌 텍스트(상호·전화번호·안내문)는 제외.
> 한 메뉴에 사이즈별 가격이 있으면 `"김치찌개(소)"` 처럼 항목을 분리.

**응답(기대)**: JSON 배열 단독. 코드펜스(```json ... ```)로 감싸져 와도 파서가 벗겨낸다.

```json
[
  { "name": "김치찌개", "price": 8000 },
  { "name": "오늘의 반찬", "price": null }
]
```

파싱 실패(배열 아님·필드 누락·JSON 아님)는 해당 샘플의 `error` 로 기록한다.

## 3. `experiment/results.json` (산출)

```json
{
  "summary": {
    "model": "gpt-4o-mini",
    "executedAt": "2026-07-14T18:00:00+09:00",
    "sampleCount": 12,
    "failureCount": 1,
    "menuNameAccuracy": 0.91,
    "priceAccuracy": 0.88,
    "avgLatencyMs": 3200,
    "totalCostUsd": 0.041,
    "totalCostKrw": 61.5,
    "avgCostPerImageKrw": 5.1
  },
  "results": [
    {
      "sampleId": "s01-printed-clean",
      "extracted": [ { "name": "김치찌개", "price": 8000 } ],
      "matched": [
        { "labelName": "김치찌개", "extractedName": "김치찌개", "labelPrice": 8000, "extractedPrice": 8000, "priceCorrect": true }
      ],
      "missing": ["공기밥"],
      "spurious": [],
      "latencyMs": 2870,
      "promptTokens": 1420,
      "completionTokens": 210,
      "costUsd": 0.0003,
      "costKrw": 0.45,
      "error": null
    },
    {
      "sampleId": "s09-expired-url",
      "extracted": [],
      "matched": [], "missing": [], "spurious": [],
      "latencyMs": 0, "promptTokens": 0, "completionTokens": 0,
      "costUsd": 0.0, "costKrw": 0.0,
      "error": "OpenAI image fetch failed: 403 (presigned URL expired)"
    }
  ]
}
```

## 4. `experiment/report.md` (결론 문서 — 수기, 템플릿)

필수 섹션: ① 실행 조건(모델·프롬프트 버전·샘플 구성) ② 지표 요약(summary 전사) ③ 오류 분석(누락/오검출/오타 사례) ④ **현행 방식(클라이언트 OCR + 텍스트 정제) 비교표** ⑤ 채택/미채택 결론과 근거 ⑥ 후속 이슈 목록(`LlmChatRequest` 이미지 입력 확장, 가격 스키마·응답 필드, `ScannedNameInterpreter` seam 재설계, Gemini URL 미지원 대응).

## 하네스 실행 계약

```bash
OPENAI_API_KEY=... ./gradlew :infra:llm:test \
  --tests "com.kbap.infra.llm.experiment.MenuBoardVisionExperimentTest" \
  -Dllm.vision.experiment.enabled=true \
  [-Dllm.vision.experiment.model=gpt-4o] \
  [-Dllm.vision.experiment.manifest=<samples.json 경로>] \
  [-Dllm.vision.experiment.output=<results.json 경로>]
```

- `llm.vision.experiment.enabled` 미설정 시 스펙 전체 skip(CI 안전 — `LlmSmokeTest` 와 동일 게이트 방식).
- manifest·output 기본값: `specs/kb-138-menu-price-mapping/experiment/{samples,results}.json`.
