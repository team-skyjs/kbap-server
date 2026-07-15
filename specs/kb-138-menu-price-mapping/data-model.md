# Data Model: KB-138 실험 데이터 구조

영속 없음 — 전부 실험 하네스 로컬 data class(테스트 소스셋) + JSON 파일이다. DB·엔티티·마이그레이션 0건.

## MenuPriceItem (공용 값)

메뉴명–가격 쌍. 정답 라벨과 LLM 추출 결과가 같은 모양을 쓴다.

| 필드 | 타입 | 규칙 |
|------|------|------|
| `name` | String | 비어 있지 않음. 메뉴판 표기 그대로(한국어 원문 기준) |
| `price` | Int? | 원 단위 정수. 가격 미표기 메뉴는 `null`. 사이즈별 다중 가격은 항목을 가격 수만큼 분리해 라벨링(예: "김치찌개(소)", "김치찌개(대)") |

## ExperimentSample (입력 — samples.json 항목)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `id` | String | 샘플 고유 식별자(예: `s01-printed-clean`) |
| `imageUrl` | String | 외부 접근 가능한 이미지 URL(파일 경로 금지 — FR-001) |
| `conditions` | List\<String\> | 촬영 조건 태그: `printed`/`handwritten`, `low-light`, `tilted`, `low-res`, `bilingual` 등 |
| `label` | List\<MenuPriceItem\> | 수기 정답(FR-004). 빈 목록 금지 |

## SampleResult (산출 — results.json 항목)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `sampleId` | String | ExperimentSample.id 참조 |
| `extracted` | List\<MenuPriceItem\> | LLM 추출 결과(실패 시 빈 목록) |
| `matched` | List\<MatchedPair\> | 정규화 일치 쌍 — `{labelName, extractedName, labelPrice, extractedPrice, priceCorrect}` |
| `missing` | List\<String\> | 라벨에만 있는 메뉴명(누락) |
| `spurious` | List\<String\> | 추출에만 있는 메뉴명(오검출) |
| `latencyMs` | Long | 호출 벽시계 지연 |
| `promptTokens` / `completionTokens` | Long | usage 메타 |
| `costUsd` / `costKrw` | Double | `LlmPricing` 계산값 |
| `error` | String? | 실패 시 원인(HTTP 상태·메시지 — URL 만료/접근 불가 포함, FR-009). 성공 시 `null` |

**상태 규칙**: `error != null` ⇔ `extracted` 빈 목록·지표 필드 0. 조용한 빈 결과 금지 — 추출 0건인데 error 도 없는 경우는 "추출 실패"가 아니라 "메뉴 0개 판정"으로 그대로 기록.

## ExperimentSummary (산출 — results.json 헤더)

| 필드 | 타입 | 규칙 |
|------|------|------|
| `model` | String | 실행에 쓴 모델명 |
| `executedAt` | String | ISO-8601 실행 시각 |
| `sampleCount` / `failureCount` | Int | 전체·실패 샘플 수 |
| `menuNameAccuracy` | Double | Σ matched / Σ 라벨 항목 (실패 샘플 제외) |
| `priceAccuracy` | Double | Σ priceCorrect / Σ 매칭 중 가격 판정 대상 |
| `avgLatencyMs` | Long | 성공 샘플 평균 |
| `totalCostUsd` / `totalCostKrw` | Double | 합계 |
| `avgCostPerImageKrw` | Double | 장당 비용(DoD 지표) |

## 관계

```text
samples.json (List<ExperimentSample>)
      │  하네스 실행(모델·프롬프트 고정)
      ▼
results.json { summary: ExperimentSummary, results: List<SampleResult> }
      │  수기 분석(오타 분류·현행 비교)
      ▼
report.md (비교표·채택 결론·후속 이슈)
```
