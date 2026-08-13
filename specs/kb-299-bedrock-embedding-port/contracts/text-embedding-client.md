# Contract: TextEmbeddingClient (seam)

`:common` — `com.kbap.common.port.llm.TextEmbeddingClient`. 순수 계약 — Spring·JPA·AWS 타입을 노출하지 않는다(ArchUnit `common.port` Spring-free 규칙 적용 대상).

```kotlin
package com.kbap.common.port.llm

fun interface TextEmbeddingClient {
    fun embed(texts: List<String>): List<FloatArray>
}
```

## 계약 규칙

1. **개수 일치·순서 보존**: 반환 목록 크기 == 입력 크기, i번째 벡터는 i번째 텍스트의 임베딩이다.
2. **차원 고정**: 모든 벡터는 1024차원이다. 제공자가 다른 차원을 반환하면 예외를 던진다(조용한 통과 금지).
3. **빈 목록 단락**: `texts`가 비면 외부 호출 없이 빈 목록을 반환한다.
4. **실패 전파**: 어떤 건이든 제공자 호출이 실패하면 전체 호출이 예외로 실패한다. 부분 성공 결과를 반환하지 않는다. 재시도·격리 정책은 호출자 소관.
5. **경로 중립**: 배치 묶음(수백~수천 건)과 동기 단건/소량(크기 1~수 개 목록) 모두 동일 계약으로 수용한다. 단건 전용 메서드는 두지 않는다.
6. **입력 불가공**: 받은 텍스트를 정규화·조립·필터링하지 않고 그대로 제공자에 전달한다(조립 규칙은 호출자 소유).

## 구현체 (이 기능 범위)

- `com.kbap.infra.llm.embedding.SpringAiTextEmbeddingClient` — Spring AI `EmbeddingModel`(`BedrockTitanEmbeddingModel`)에 위임 + 규칙 2·3 이행.
- 빈 조립: `LlmConfiguration` — `kbap.llm.embedding.enabled=true`일 때만 생성.
