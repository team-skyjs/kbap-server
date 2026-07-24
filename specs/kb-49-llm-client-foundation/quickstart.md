# Quickstart: LLM 호출 토대 (`:infra:llm`)

## 1. 부팅 안전 확인 (키 없이)

기본 상태(모든 `meogo.llm.*.enabled=false`)에서 배치가 회귀 없이 뜨는지 확인한다.

```bash
./gradlew :infra:llm:test        # fan-out 단위(페이크) + 프로퍼티 바인딩 + 부팅 안전
./gradlew :app:batch:test        # 배치 부팅 안전(키 없음 컨텍스트 로딩)
./gradlew build                  # 전체 컴파일/테스트
```

기대: 키·활성 플래그가 전혀 없어도 컨텍스트가 로딩되고 LLM 빈은 0개다.

## 2. 로컬에서 모델 활성화

루트 `.env`(git-ignored)에 키를 넣는다.

```properties
OPENAI_API_KEY=sk-...
UPSTAGE_API_KEY=up-...
GEMINI_API_KEY=AIza...
```

활성화할 모델의 `enabled=true` 와 키/모델명을 프로필에 지정한다. `meogo.llm.*` 스키마는 [contracts/llm-client-properties.md](./contracts/llm-client-properties.md) 참고.

## 3. 실키 스모크 검증 (수동, FR-011)

3개 모델 각 1회 실호출 스모크는 평소 `@Disabled` 다. 로컬에서 키를 채운 뒤 수동 실행한다.

```bash
./gradlew :infra:llm:test --tests "*LlmSmokeTest" -Dllm.smoke.enabled=true
```

기대: OpenAI·Upstage·Gemini 각각 성공 → `LlmFanoutResult.successes.size == 3`, `failures` 비어 있음.

## 4. 배치 잡에서 사용 (후속 배치용 참고)

`:app:batch` 가 `:infra:llm` 를 `implementation` 으로 의존하므로, 잡이 클라이언트를 주입받아 호출한다.

```kotlin
// com.meogo.app.batch.user.SomeCollectionJob
class SomeCollectionJob(private val llm: LlmFanoutClient) {   // com.meogo.infra.llm
    fun run() {
        val result = llm.generate(LlmChatRequest(prompt = "..."))
        result.successes.forEach { /* modelId, content */ }
        result.failures.forEach  { /* modelId, message  */ }
    }
}
```

> 배치는 단일 `:app:batch` bootJar — 잡은 `com.meogo.app.batch.user` / `com.meogo.app.batch.admin` 패키지로 구분한다.

## 5. 모듈/의존 정합 확인 (SC-005)

```bash
grep -n ":infra:llm" settings.gradle.kts app/batch/build.gradle.kts
```

기대: `settings.gradle.kts` 에 `include(":infra:llm")`, `app/batch/build.gradle.kts` 에 `implementation(project(":infra:llm"))`. 결정 근거는 `docs/adr/0010-llm-adapter-module-named-infra-llm.md`. (범용 `:infra:external` catch-all 서술은 범위 밖 — 갱신 대상 아님.)
