# Research: 배치 콘텐츠 4개 작업용 LLM 클라이언트

**Date**: 2026-07-22 | **Plan**: [plan.md](plan.md)

Technical Context 에 NEEDS CLARIFICATION 은 없었고, 아래는 설계 선택지가 갈리는 지점의 결정 기록이다.

## D1. 구현 위치와 빈 조립

- **Decision**: 4개 구현체를 `:infra:llm` 의 `com.kbap.infra.llm.food` 패키지에 두고, 빈 조립은 모듈 안 `FoodContentClientConfiguration`(`@ConditionalOnProperty`)이 담당한다.
- **Rationale**: CLAUDE.md 격리 패턴("인터페이스는 소비 계층에, 구현은 `:infra:*` 에") 그대로. `:infra:llm` 은 조립 `@Configuration` 을 예외적으로 모듈 안에 두는 관례(api·batch 양쪽 소비)가 이미 있다. `:infra:llm` → `:core` 의존이 이미 있어 seam 참조에 빌드 변경이 없다.
- **Alternatives considered**: `:app:batch` 안 직접 구현(유일 소비자) — infra 격리 관례 위반, api 재사용 여지 상실로 기각.

## D2. 텍스트 작업(번역·설명)의 모델·호출 방식

- **Decision**: 기존 OpenAI `LlmModelCaller` 빈(`openAiModelCaller`)을 이름으로 주입해 **1건씩 호출**한다. structured output 은 프롬프트로 JSON 형식을 지시하고 jackson 으로 파싱한다(코드펜스 제거 포함 — `MenuBoardResultParser` 선례를 `FoodContentJsonParser` 로 일반화).
- **Rationale**: 배치 파이프라인 결정(음식 1건 단위 처리, LLM 호출 기본 1건씩)과 정합. 번역·설명은 안전 직결이 아니라 fan-out 비용(3배)이 정당화되지 않는다. Spring AI 의 `BeanOutputConverter` 대신 수동 파싱을 쓰는 것은 기존 선례(`MenuBoardResultParser`)와의 일관성 + `LlmModelCaller` seam(String 반환)을 그대로 재사용하기 위함.
- **Alternatives considered**: (1) 3모델 fan-out 후 첫 성공 채택 — 비용 3배·이득 없음, 기각. (2) Spring AI `ChatClient.entity()` structured output — `LlmModelCaller` seam 을 우회해 토큰 로깅·페이크 테스트 기틀을 잃음, 기각.

## D3. 기피성분 조사 — fan-out 종합 규칙

- **Decision**: 기존 `LlmFanoutClient`(OpenAI·Upstage·Gemini) 를 재사용한다. 프롬프트는 **모든 candidateCodes 각각에 대해 0..100 포함 확률**을 반환하도록 지시한다(미포함 = 0). 종합은:
  1. 각 성공 응답을 파싱·검증 — 후보 밖 코드·범위 밖 percent 가 있으면 그 **모델 응답을 실패로 강등**한다.
  2. 유효 응답이 **2개 미만이면 예외 전파**(복수 모델 종합 불가 — 안전 직결이라 단일 모델 판단 금지, SC-003).
  3. 코드별로 유효 응답들의 percent **평균(정수 반올림)** 을 취하고, 결과가 0 인 코드는 제외해 반환한다.
- **Rationale**: "전 후보 코드에 대해 판단" 프롬프트로 모델 간 언급/미언급 비대칭 문제를 제거해 종합 규칙을 단순 평균으로 유지한다. 과반(2/3) 최소 기준은 spec 엣지 케이스("일부 실패 → 종합, 종합 불가 수준 → 실패")의 구체화다.
- **Alternatives considered**: (1) 언급한 모델만 평균 — 모델별 응답 코드 집합이 달라 종합 의미가 흔들림, 기각. (2) 최소 성공 1개 허용 — 단일 모델 판단이 되어 SC-003 위반, 기각. (3) max 채택(가장 보수적) — 오탐 과다로 데이터 품질 저하, 평균 대비 이득 불명확, 기각.

## D4. 사진 생성 — 이미지 모델과 저장

- **Decision**: Spring AI OpenAI 이미지 모델(`OpenAiImageModel`, 기존 `spring-ai-starter-model-openai` 에 포함 — 신규 의존 없음)로 b64 이미지를 받아, `:core` `StorageObjectStore` seam 에 **`put(path, bytes, contentType)` 을 추가**해 storageKey 위치에 업로드한 뒤 키를 반환한다. 설정은 `kbap.llm.image.*` (vision 선례의 독립 프로퍼티 + `@ConditionalOnProperty`).
- **Rationale**: S3 업로드(PutObject)는 같은 키 덮어쓰기라 멱등성(FR-006)이 공짜로 온다. seam 확장은 head/delete 가 이미 있는 `StorageObjectStore` 에 put 하나 추가가 최단 경로 — 새 seam 을 만들면 조각만 는다. 저장 완료 후 키 반환(FR-005)은 put 성공 후 return 으로 자연 보장.
- **Alternatives considered**: (1) Gemini 이미지 생성 — google-genai 스타터의 이미지 지원이 chat 과 별개 API 라 구성 추가가 더 큼, 기각. (2) presigned URL 경유 업로드 — 서버 내부 업로드에 presign 은 불필요한 간접, 기각. (3) 이미지 전용 새 seam — 기존 seam 확장으로 충분, 기각.

## D5. 계약 검증·실패 전파

- **Decision**: 1차 검증은 계약 DTO 의 init 불변식(9언어 전수·blank 금지·255자·플레이스홀더 금지·맵기 0..10·percent 0..100)에 위임한다 — 파싱 결과로 DTO 를 생성하는 순간 위반이 예외가 된다. DTO 가 모르는 제약(code ∈ candidateCodes)만 구현체가 직접 검증한다. 재시도는 하지 않는다(호출자/배치 소관 — spec Assumptions).
- **Rationale**: 검증 로직 중복 제거 — 불변식의 단일 출처는 `:core` DTO 다. 배치 Step 이 `faultTolerant().skip(Exception)` 으로 건 단위 격리를 이미 수행하므로(SC-002) 예외 전파만 하면 된다.
- **Alternatives considered**: 구현체에서 위반 시 1회 재프롬프트 — 비용·복잡도 추가, 배치 재실행이 이미 재시도 역할, 기각(필요해지면 후속).

## D6. 배치 조립(StorageObjectStore)

- **Decision**: `:app:batch` 가 `:infra:storage` 를 `implementation` 의존하고, `BatchStorageConfig` 가 `S3StorageObjectStore.create(region, bucket)` 로 빈을 조립한다(api 의 `StorageConfig` 선례). 미구성 환경에선 빈이 없고, 이미지 클라이언트 빈도 `@ConditionalOnProperty` 로 함께 미생성 — 부팅 안전.
- **Rationale**: "구현은 `:infra:*`, 조립은 부트앱 config" 패턴 그대로. 배치 컴포넌트 스캔은 자신 + `com.kbap.infra.llm` 이므로 storage 조립은 배치 자신의 config 패키지에 둔다.
- **Alternatives considered**: `:infra:llm` 이 `:infra:storage` 를 의존 — infra 간 수평 의존 신설, seam(`:core`) 경유로 충분, 기각.
