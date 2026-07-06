# Contract: LlmFanoutClient (`:infra:llm` 공개 API)

배치 잡이 LLM 을 호출할 때 쓰는 **공개 진입점**. `:app:batch` 가 `:infra:llm` 를 `implementation` 으로 의존해 이 클라이언트 빈을 주입받아 호출한다. (kernel port 는 두지 않음 — 배치가 이 모듈을 직접 의존. web/application 재사용 시 kernel port 승격은 후속.)

## API

```
package com.meogo.infra.llm

class LlmFanoutClient(
    private val callers: List<LlmModelCaller>,
    private val executor: Executor,
) {
    fun generate(request: LlmChatRequest): LlmFanoutResult
}
```

## 입력 — LlmChatRequest
| 필드 | 타입 | 필수 | 규칙 |
|------|------|------|------|
| prompt | String | ✅ | blank 금지(`require(prompt.isNotBlank())`) |
| system | String? | ❌ | 시스템 지시(옵션) |

## 출력 — LlmFanoutResult
| 필드 | 타입 | 의미 |
|------|------|------|
| successes | List<LlmChatResult> | 성공한 모델별 결과(modelId + content) |
| failures | List<LlmModelFailure> | 실패한 모델별 사유(modelId + message) |

## 동작 계약(불변식)
1. **병렬성** — 활성 N개 모델을 동시에 호출. 총 소요는 순차 합이 아니라 가장 느린 단일 호출에 수렴.
2. **부분 실패 격리** — 한 모델의 예외/타임아웃은 다른 모델 결과 수집을 막지 않는다. 실패 모델은 `failures` 로 분리.
3. **전멸/전비활성 무예외** — 활성 0개이거나 전부 실패해도 예외를 던지지 않고, 각각 빈/채워진 결과를 반환.
4. **모델 귀속** — 모든 결과·실패는 `LlmModelId` 로 어느 모델 소산인지 식별 가능.
5. **중복 없음** — 동일 `modelId` 가 successes 와 failures 에 동시 등장하지 않는다.
6. **벤더 중립** — 입출력에 Spring AI/벤더 SDK 타입 미노출(어댑터 내부에서만 사용).

## 테스트 계약 (헌법 I)
- `callers` 는 `LlmModelCaller` 인터페이스 리스트 → 테스트는 **페이크**(정상/예외/지연)를 주입해 불변식 1~5 를 실 네트워크 없이 검증한다.

## 수용 시나리오 → 계약 매핑
- spec US1 #1 (전부 정상) → successes=N, failures=0
- spec US1 #2 (1개 실패) → successes=N-1, 해당 modelId 는 failures 에
- spec US1 #3 (병렬성) → 불변식 1
- spec US1 #4 (활성 0) → successes=[], failures=[]
