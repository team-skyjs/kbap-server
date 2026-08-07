# Quickstart: 임베딩 생성 포트

## 활성화 (기본은 꺼짐 — 부팅 안전)

```yaml
kbap:
  llm:
    embedding:
      enabled: true
      # 아래는 기본값 — 필요 시만 오버라이드
      # model: amazon.titan-embed-text-v2:0
      # region: ap-northeast-2
      # dimension: 1024
```

자격증명은 AWS 기본 체인으로 해석된다 — 배포 환경은 EC2 인스턴스 역할(`bedrock:InvokeModel` 부착 완료), 로컬은 `aws configure` 프로필. API 키 프로퍼티는 없다.

## 사용

```kotlin
class SomeCaller(private val embeddingClient: TextEmbeddingClient) {
    fun run() {
        val vectors: List<FloatArray> = embeddingClient.embed(listOf("김치찌개 | 돼지고기와 김치를 끓인 찌개"))
        check(vectors.single().size == 1024)
    }
}
```

## 테스트

```bash
./gradlew :infra:llm:test                          # 페이크 단위 + 구성 테스트 (AWS 불필요)
./gradlew :infra:llm:test --tests "*EmbeddingSmokeTest" -Dembedding.smoke.enabled=true   # 실호출 스모크(AWS 자격증명 필요)
./gradlew build                                     # 전체 회귀(미설정 부팅 안전 포함)
```
