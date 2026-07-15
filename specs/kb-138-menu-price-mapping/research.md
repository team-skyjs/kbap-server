# Research: KB-138 메뉴판 사진 → 메뉴명·가격 추출 실험

## R1. 이미지 입력 방식 — URL 전달 vs 파일 업로드

- **Decision**: 이미지 **URL 을 그대로 LLM API 에 전달**한다. 파일 바이트는 하네스/서버를 통과하지 않는다.
- **Rationale**: OpenAI chat API 의 vision 입력은 `image_url` 타입을 네이티브 지원하며 **OpenAI 서버가 URL 을 직접 fetch** 한다. 실환경 전제(클라이언트 → S3 presigned URL 업로드 → 백엔드는 링크만 수신)와 정확히 같은 흐름이므로, 실험이 곧 실환경 검증이 된다. presigned URL 은 fetch 시점까지 유효하면 되므로 TTL 을 호출 지연보다 넉넉히 잡으면 충분하다.
- **Alternatives considered**:
  - base64 data URL 인라인 — 파일 바이트가 하네스를 통과해 FR-001 위반. 토큰 측정도 실환경과 달라짐. 기각.
  - OpenAI Files API 업로드 — 업로드 단계 추가로 실환경 흐름과 불일치. 기각.
- **주의(후속 이슈용 기록)**: URL-fetch 방식은 OpenAI 계열 기준. Gemini(google-genai)는 임의 HTTP URL 을 같은 방식으로 받지 않아(File API/inline 바이트 위주) 3모델 fan-out 확장 시 벤더별 분기가 필요하다.

## R2. 호출 스택 — 기존 `:infra:llm` 재사용 범위

- **Decision**: 하네스가 **Spring AI `OpenAiChatModel` 을 직접 생성**해 호출한다. `LlmFanoutClient`·`LlmChatRequest`·`SpringAiModelCaller` 는 쓰지 않는다.
- **Rationale**: `LlmChatRequest` 는 텍스트 `prompt` 만 받는다(이미지 미지원). 이를 확장하면 프로덕션 코드 변경(FR-008 위반)이 된다. 같은 모듈 테스트 소스셋이므로 `LlmConfiguration.openAiChatOptions`(internal)와 `LlmPricing` 을 그대로 재사용해 옵션 구성·비용 계산 중복을 피한다. "`:infra:llm` 확장이 필요한가"라는 Jira 의 질문에 대한 답 자체가 실험 산출물이다 — 답: **필요하다(`LlmChatRequest` 에 이미지 입력 개념이 없음), 후속 이슈로**.
- **Alternatives considered**:
  - `LlmChatRequest` 에 `imageUrls` 필드 추가 — 프로덕션 diff 발생, 채택 결정 전 선반영. 기각.
  - Java HttpClient 로 raw JSON 직접 호출 — 동작은 하지만 Spring AI 가 이미 클래스패스에 있고 토큰 usage 파싱을 공짜로 준다. 기각(ponytail: 있는 것 재사용).

## R3. 이미지 URL 을 Spring AI 로 싣는 방법

- **Decision**: `UserMessage` 에 URL 기반 `Media`(mimeType + `URI`)를 첨부한다 — `UserMessage.builder().text(prompt).media(Media(MimeTypeUtils.IMAGE_JPEG, URI(...)))` 계열 API. OpenAI 모델 구현이 URL Media 를 `image_url` 파트로 직렬화한다.
- **Rationale**: Spring AI 멀티모달 공식 지원 경로. 바이트 로드 없이 URL 참조만 전달된다.
- **Fallback**: Red 단계에서 Spring AI 2.0 의 정확한 시그니처(생성자 vs builder)가 다르면 같은 모듈의 Spring AI 버전 소스에 맞춰 조정한다. URL Media 가 base64 로 강제 변환되는 동작이 확인되면(그럴 경우 FR-001 위반) raw `RestClient` 호출로 전환한다 — 계약(contracts/experiment-files.md 의 요청 형태)은 동일.

## R4. 구조화 출력 전략

- **Decision**: 프롬프트로 JSON 배열 출력을 지시하고, 응답에서 **코드펜스 제거 후 Jackson 파싱**하는 관대한 파서(`MenuPriceParser`)를 둔다.
- **Rationale**: 기존 `ScannedNameParser` 가 같은 접근으로 검증됨. 스파이크에 json_schema 강제까지는 불필요.
- **Alternatives considered**: OpenAI `response_format: json_schema` — 파싱 실패율이 유의미하게 나오면 2차 시도로 채택(그 자체도 실험 데이터). 첫 실행부터 쓰지 않는 이유: 프롬프트만으로 어느 정도 나오는지도 측정 대상.

## R5. 실험 대상 모델

- **Decision**: 기본 `gpt-4o-mini`(vision 지원·저비용), 시스템 프로퍼티 `llm.vision.experiment.model` 로 교체 가능(예: `gpt-4o`).
- **Rationale**: 비용 하한선(mini)에서 시작해 품질 미달 시 상위 모델 재실행 — 두 실행의 지표 비교가 곧 DoD 의 비용/품질 데이터가 된다. 프로퍼티 주입은 기존 `kbap.llm.openai.model` 관례와 동형.

## R6. 지표 정의(측정 가능한 형태로 고정)

- **Decision**:
  - **메뉴명 매칭**: 라벨·추출 양쪽 이름을 정규화(트림·공백 제거) 후 정확 일치 → `MATCHED`. 라벨에만 있으면 `MISSING`(누락), 추출에만 있으면 `SPURIOUS`(오검출). 정규화 후에도 불일치하는 근사 쌍(오타)은 리포트에서 수기 분류 — 자동 유사도 매칭은 스파이크 범위 밖.
  - **메뉴명 정확도** = MATCHED / 라벨 항목 수. **가격 정확도** = 가격까지 일치한 MATCHED / 라벨 중 가격 있는 항목 수(가격 없음(null)도 일치 판정 대상).
  - **지연** = 호출 전후 벽시계(ms). **토큰·비용** = `ChatResponse.metadata.usage` + `LlmPricing`(모델 단가는 manifest 실행 시점 프로퍼티로 주입).
- **Rationale**: FR-005 의 "누락·오검출·오타 구분"을 판정 가능한 최소 규칙으로 고정. 자동 오타 판정(편집거리)은 샘플 20장 수준에서 눈으로 보는 게 더 싸다.

## R7. 샘플 이미지 호스팅

- **Decision**: 실험자가 **외부에서 접근 가능한 URL** 을 준비해 manifest 에 기입한다(S3 presigned URL 권장 — 실환경과 동일, TTL 은 실험 세션보다 길게. GitHub raw 등 공개 URL 도 무방).
- **Rationale**: 호스팅 방식은 추출 품질과 무관하고 실험 범위 밖. 리포 커밋은 사진 저작권·용량 문제가 있어 기본 배제(원하면 나중에).
- **실패 처리**: 만료·접근 불가 URL 은 OpenAI 가 fetch 실패 에러를 반환 — 하네스는 샘플별 실패 원인을 `results.json` 에 기록하고 다음 샘플로 진행(전체 중단 금지, FR-009).

## 미해결 NEEDS CLARIFICATION

없음.
