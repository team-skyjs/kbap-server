# Phase 1 Data Model: LLM 호출 토대

이 기능은 **영속 엔티티가 없다**(DB 무변경). 여기서 "모델"은 `:infra:llm` 의 공개 계약 값타입과 어댑터 구성요소를 뜻한다. 모든 값타입은 **불변**(val), 도메인 규약대로 Kotlin 주석 없이 self-documenting 이름으로 작성한다. 전부 `com.meogo.infra.llm` 패키지.

## A. 공개 값타입 (배치가 보는 계약 — 벤더 중립)

### LlmModelId (enum)
- 값: `OPENAI`, `UPSTAGE`, `GEMINI`
- 의미: fan-out 대상·결과·실패를 식별. 컴파일타임 망라 매칭(`when`). 모델 추가 = 상수 + 빈 추가.

### LlmChatRequest (value)
- `prompt: String` — 필수, blank 금지(`require`).
- `system: String? = null` — 선택 시스템 지시.
- (확장 여지) `maxTokens: Int? = null`, `temperature: Double? = null` — 후속 잡이 필요로 하면 추가. 토대에선 최소.
- 규칙: Spring AI 타입 미참조.

### LlmChatResult (value) — 단일 모델 성공
- `modelId: LlmModelId`
- `content: String`

### LlmModelFailure (value) — 단일 모델 실패
- `modelId: LlmModelId`
- `message: String` — 실패 사유 요약. 벤더 예외 타입은 담지 않음.

### LlmFanoutResult (value) — 집계
- `successes: List<LlmChatResult>`
- `failures: List<LlmModelFailure>`
- 파생: `isAllFailed(): Boolean = successes.isEmpty()`, `attemptedCount(): Int = successes.size + failures.size`.
- 불변식: 한 `modelId` 는 successes/failures 중 한쪽에만(중복 없음).

### LlmFanoutClient (class) — 공개 API (배치가 주입받아 호출)
- `fun generate(request: LlmChatRequest): LlmFanoutResult`
- 필드: `callers: List<LlmModelCaller>`, `executor: java.util.concurrent.Executor`(가상 스레드).
- `generate`: 각 caller 를 `CompletableFuture.supplyAsync({ caller.call(request) }, executor)` 로 병렬 실행 → future 별 `handle` 로 성공(`LlmChatResult`)·실패(`LlmModelFailure`) 분할 → `join` 후 `LlmFanoutResult` 조립. 예외를 밖으로 던지지 않음.

## B. 내부 어댑터 구성요소

### LlmModelCaller (interface, 단일모델 seam)
- `val modelId: LlmModelId`
- `fun call(request: LlmChatRequest): String` — 단일 모델 1회 동기 호출(성공 시 content, 실패 시 예외 throw).
- 목적: `LlmFanoutClient` 가 이 리스트를 반복하며 fan-out. 테스트는 이 인터페이스의 **페이크**(정상/예외/지연)로 병렬·부분실패를 실 네트워크 없이 검증(FR-010, 헌법 I).

### SpringAiModelCaller (class) — `LlmModelCaller` 구현
- 필드: `modelId: LlmModelId`, `chatModel: org.springframework.ai.chat.model.ChatModel`.
- `call`: `LlmChatRequest` → Spring AI `Prompt`(system+user) 변환 → `chatModel.call(prompt)` → 응답 텍스트 추출 → 반환. 벤더 응답을 계약(String)으로 매핑(변환은 어댑터 책임).

### LlmModelProperties (@ConfigurationProperties("meogo.llm"))
- `openai: ModelProps`, `upstage: ModelProps`, `gemini: ModelProps`
- `ModelProps`: `enabled: Boolean = false`, `apiKey: String? = null`, `baseUrl: String? = null`, `model: String? = null`
- 바인딩: relaxed. 값 원천 — 루트 `.env`/OS env(`spring.config.import`).

### LlmConfiguration (@Configuration)
- `openAiModelCaller()` `@ConditionalOnProperty("meogo.llm.openai.enabled", havingValue="true")` — OpenAI 엔드포인트로 `OpenAiChatModel` → `SpringAiModelCaller(OPENAI, …)`.
- `upstageModelCaller()` `@ConditionalOnProperty("meogo.llm.upstage.enabled", …)` — OpenAI 클래스 재사용 + `baseUrl`=Upstage → `SpringAiModelCaller(UPSTAGE, …)`.
- `geminiModelCaller()` `@ConditionalOnProperty("meogo.llm.gemini.enabled", …)` — google-genai ChatModel → `SpringAiModelCaller(GEMINI, …)`.
- `llmFanoutExecutor()` — `Executors.newVirtualThreadPerTaskExecutor()`.
- `llmFanoutClient(callers: List<LlmModelCaller>, executor)` — 공개 API 빈(주입 리스트가 비어도 생성).

## 상태 전이
해당 없음 — 무상태 호출 어댑터. (LLM 결과의 저장/확정은 이 토대를 소비하는 후속 배치 잡의 책임.)
