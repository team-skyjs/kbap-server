# Research: 임베딩 생성 포트 및 인프라 어댑터

Phase 0 산출물 — Technical Context 의 미확정 사항을 전부 해소했다. NEEDS CLARIFICATION 잔여 없음.

## D1. 호출 방식: Spring AI 2.0 Bedrock 스타터

- **Decision**: `org.springframework.ai:spring-ai-starter-model-bedrock`(버전은 기존 `spring-ai-bom:2.0.0`이 관리)를 `:infra:llm`에 추가하고, `BedrockTitanEmbeddingModel` + `TitanEmbeddingBedrockApi`를 명시 빈으로 구성해 사용한다.
- **Rationale**: 사용자 지시("spring ai 기반") + `:infra:llm`이 이미 Spring AI 스타터 조합(openai·google-genai) 패턴이라 결이 같다. Maven Central 실측 — 스타터 2.0.0 존재, 코어 `spring-ai-bedrock-2.0.0.jar` 안에 `org.springframework.ai.bedrock.titan.BedrockTitanEmbeddingModel`·`api.TitanEmbeddingBedrockApi` 확인(2026-08-07). `BedrockTitanEmbeddingModel`은 Spring AI 공통 `EmbeddingModel` 계약(`embed(List<String>): List<float[]>`)을 구현하므로 어댑터가 위임만 하면 된다.
- **Alternatives considered**: AWS SDK v2 `bedrockruntime` 직접 호출(InvokeModel + JSON 수십 줄) — 코드량은 비슷하나 기존 모듈의 Spring AI 패턴·BOM 버전 관리에서 벗어나고, 스타터가 어차피 SDK 를 전이시키므로 이점 없음. 기각.

## D2. 모듈 위치: 기존 `:infra:llm` 확장 (신규 모듈 없음)

- **Decision**: seam 은 `common.port.llm.TextEmbeddingClient`, 구현은 `com.kbap.infra.llm.embedding` 패키지.
- **Rationale**: KB-299 티켓 작업 순서 3이 이미 ":infra:llm 에 Bedrock 임베딩 클라이언트"로 확정. `common.port` 분류 기준은 "구현 모듈 기준"(CLAUDE.md)이라 llm 하위가 맞다. 임베딩도 LLM 모델 호출의 일종이라 의미상도 정합.
- **Alternatives considered**: 신규 `:infra:embedding` 모듈 — 어댑터 클래스 1개를 위한 모듈은 과분리. 기각.

## D3. seam 계약 형태

- **Decision**: `fun interface TextEmbeddingClient { fun embed(texts: List<String>): List<FloatArray> }` — 경로 중립(배치 묶음·동기 단건 모두 목록으로 수용, 단건은 크기 1 목록). Spring·JPA·AWS 타입 비노출.
- **Rationale**: clarify 세션 확정(경로 중립). 기존 seam 스타일(`fun interface FoodNameTranslationClient`) 미러링. `FloatArray`는 1024차원 수치 벡터의 자연 표현이고 Spring AI `EmbeddingModel.embed`의 `float[]`와 변환 마찰이 없다.
- **Alternatives considered**: 단건 `embed(text: String)` 별도 메서드 — 크기 1 목록으로 충분, YAGNI. 벡터 값객체 래핑 — 소비처(벡터 DB 적재) 미확정 상태에서 과설계. 둘 다 기각.

## D4. 모델·차원: Titan Text Embeddings V2, 기본값 의존 + 어댑터 차원 검증

- **Decision**: 모델 id `amazon.titan-embed-text-v2:0`(`TitanEmbeddingModel.TITAN_EMBED_TEXT_V2` enum 존재 확인). dimensions·normalize 는 **모델 기본값**(1024, normalized)에 의존하고, 어댑터가 응답 벡터 차원을 검증해 1024가 아니면 예외를 던진다. 기대 차원은 프로퍼티(`kbap.llm.embedding.dimension`, 기본 1024)로 둔다.
- **Rationale**: Spring AI 2.0의 `TitanEmbeddingRequest`는 `inputText`/`inputImage` 필드만 있고 **dimensions·normalize 파라미터를 지원하지 않는다**(sources jar 실측). Titan V2 의 기본 출력이 1024·정규화라 KB-299 확정값과 일치 — 파라미터 없이도 요건 충족. 차원 검증은 스펙 엣지 케이스("약속과 다른 차원 → 오류") 이행.
- **Alternatives considered**: dimensions 명시를 위해 SDK 직접 호출로 전환 — 기본값이 이미 1024라 불필요. 기각.

## D5. 빈 구성·자격증명: `kbap.llm.embedding.*` + `@ConditionalOnProperty`, AWS 기본 자격증명 체인

- **Decision**: `LlmConfiguration`에 `@ConditionalOnProperty(prefix = "kbap.llm.embedding", name = ["enabled"], havingValue = "true")` 빈 추가. `LlmModelProperties`에 `EmbeddingProps`(model·region·dimension·timeout) 확장. 자격증명은 `TitanEmbeddingBedrockApi(modelId, region, timeout)` 생성자(AWS 기본 자격증명 체인 — EC2 인스턴스 역할/로컬 프로필)를 쓴다. API 키 프로퍼티 없음.
- **Rationale**: 기존 LLM 3종과 동일 패턴(부팅 안전·명시 구성). Bedrock 권한은 IAM(EC2 역할 부착 완료·SCP 허용 실측)으로 해결되므로 키 주입이 필요 없다 — 이 점이 openai 류와 다른 부분. `spring.ai.model.embedding: none`이 api·batch 양쪽 `application.yml`에 **이미 존재**(실측)해 스타터 자동구성 유입 차단은 무변경.
- **Alternatives considered**: 자격증명 명시 주입(access key 프로퍼티) — EC2 역할·로컬 체인으로 충분하고 시크릿 관리 지점만 늘린다. 기각.

## D6. 테스트 전략

- **Decision**: ① 어댑터 단위 — 페이크 `EmbeddingModel`(Spring AI 인터페이스)로 순서 보존·개수 일치·차원 검증·빈 목록 단락·예외 전파를 BehaviorSpec 검증. ② 구성 — `ApplicationContextRunner`로 enabled 미설정 시 빈 미생성/설정 시 생성(`LlmConfigurationBootSafetyTest` 패턴 확장). ③ 스모크 — 자격증명 있는 환경에서만 도는 조건부 실호출 테스트(`LlmSmokeTest` 패턴, 기본 비활성).
- **Rationale**: 헌법 원칙 I + 기존 `:infra:llm` 테스트 구조 그대로. `BedrockTitanEmbeddingModel`이 텍스트당 InvokeModel 1회 루프임을 확인했으므로(sources 실측) 묶음 계약 검증은 페이크 수준에서 충분하다.
- **Alternatives considered**: LocalStack/Testcontainers Bedrock 목킹 — Bedrock 은 LocalStack 무료판 미지원이고 페이크로 계약 검증이 충분. 기각.
