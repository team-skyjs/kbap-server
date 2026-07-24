# Contract: 관리자 음식 이미지 일괄 제출

**Date**: 2026-07-24 | **Plan**: [../plan.md](../plan.md)

## POST /api/v1/admin/foods/images

이미지가 필요한 음식을 OpenAI Batch API에 일괄 제출한다. 생성 완료를 기다리지 않고 즉시 응답한다. KB-186 관리자 일괄 적재(`POST /api/v1/admin/foods`) 선례 옆에 위치.

### Request

바디 없음. 제출 대상은 서버가 선정한다: `imageRef` 부재 AND 진행 중 배치(PENDING item) 미포함. 이 조건이 중복 제출 가드를 겸한다 — 연타해도 재제출 없음.

### Response 200 (BaseResponse 봉투)

```json
{
  "payload": {
    "submittedBatchCount": 3,
    "submittedFoodCount": 25
  }
}
```

- 후보 0건이면 `submittedBatchCount: 0, submittedFoodCount: 0`으로 정상 응답.
- 100건 단위 분할 제출(마지막 배치는 나머지).

### 실패

- OpenAI 업로드/배치 생성 실패: 이미 생성된 배치의 메타 기록은 유지, 실패 지점 이후 음식은 미제출로 남음 → 재호출 시 빠진 것만 재제출(멱등).

## 내부 스케줄 (외부 계약 아님 — 동작 명세)

`FoodImageCollectScheduler`: `@Scheduled`(3시간 주기, 하루 8회) + `@SchedulerLock(name = "food-image-collect", lockAtMostFor = "30m")`.

1. `image_batch WHERE batch_status = 'SUBMITTED'` 조회 (0건이면 no-op)
2. 배치별 OpenAI 상태 GET:
   - `in_progress` 등 진행 중 → 스킵
   - `completed` → 결과 JSONL 줄 단위 스트리밍: 항목별로 S3 put(`images/food/{foodId}.png` — 무접두, KB-171) → 짧은 트랜잭션(food.imageRef 갱신 + 수렴 전이 + item DONE) → LlmCallCostIncurred 발행. PENDING 아닌 항목(이미 DONE)은 건너뜀(멱등 재회수). 전 항목 처리 후 배치 COLLECTED
   - `failed`/`expired`/`cancelled` → output 파일의 부분 완료분은 회수(DONE)하고 결과 없는 PENDING 만 FAILED(error_msg) + 배치 FAILED — 이미 과금된 완성 이미지 유실 방지

## 포트 계약: FoodImageBatchClient (`:core`)

```kotlin
interface FoodImageBatchClient {
    fun submit(entries: List<Entry>): String            // Entry(customId, prompt) → openai_batch_id
    fun status(openaiBatchId: String): BatchStatus      // COMPLETED(outputFileId)/IN_PROGRESS/FAILED/EXPIRED
    fun streamResults(outputFileId: String, onItem: (Result) -> Unit)
    // Result(customId, bytes: ByteArray?, errorMessage: String?, usage: Usage?)
}
```

- `streamResults`는 줄 단위 스트리밍 — 파일 전체를 메모리에 올리지 않는다(상주 = 이미지 1장).
- 구현: `:infra:llm` `OpenAiFoodImageBatchClient` (RestClient, 기존 OpenAI 키 재사용). 테스트는 페이크로 대체.
