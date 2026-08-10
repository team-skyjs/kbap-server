# Phase 0 Research: 스캔 비전 모델 교체 및 사진 단독 판독

**Feature**: kb-320-scan-vision-ocr-ignore | **Date**: 2026-08-11

## R1. "5.6 luna" 모델 식별

- **Decision**: OpenAI `gpt-5.6-luna`. 제공자·엔드포인트는 현행 그대로(OpenAI 호환, `https://api.openai.com/v1`).
- **Rationale**: 사용자가 공식 모델 문서(`developers.openai.com/api/docs/models/gpt-5.6-luna`)를 지정했다. 이미지 입력·structured outputs 를 모두 지원해 현행 호출 형태(사진 URL + JSON 객체 응답)를 바꾸지 않아도 된다. Chat Completions API 지원 → Spring AI `OpenAiChatModel` 그대로 사용.
- **확인된 사양**: 입력 $0.2 / 출력 $1.2 (per 1M), 컨텍스트 1,050,000, 최대 출력 128,000, **추론 토큰 사용**, 지식 컷오프 2026-02-16, 캐시 입력 $0.02/1M, 272K 초과 입력은 프리미엄(입력 2배·출력 1.5배).
- **Alternatives considered**: Upstage·Gemini 계열 — vision 경로가 OpenAI 클라이언트에 묶여 있어 base-url·provider 분기가 필요했다. 문서 확인으로 불필요해짐.

## R2. 추론 모델의 파라미터 호환성 (미확정 — 배포 전 실측으로 닫는다)

- **문제**: 현재 vision 설정은 `temperature: 0.0` 을 보낸다(`application.yml`, `visionChatOptions` 가 non-null 일 때만 실음). OpenAI 추론 계열 모델은 기본값 외 `temperature` 를 거부하는 사례가 있고, 모델 문서 페이지에는 파라미터 호환성 표가 없다.
- **Decision**: `kbap.llm.vision.temperature` 를 **`1.0` 으로 올린다**(2026-08-11 사용자 확정). 코드 변경 0줄, 설정 1줄 수정.
- **Rationale**: `1.0` 은 OpenAI 기본값이다. 추론 계열 모델은 기본값 외 temperature 를 거부하는 사례가 있으므로, 기본값을 명시적으로 보내면 거부 리스크가 사라진다. 온도 0 의 목적(결정적 판정)은 추론 모델에서 애초에 보장되지 않아 잃는 것도 없다.
- **기각된 사용자 초안 값**: `10` — OpenAI temperature 허용 범위는 `0~2` 라 API 가 400 으로 거절한다. 범위 안(`2.0`)이었더라도 메뉴명·가격 판독에서 출력이 흔들려 이 기능의 목표(정확도 개선)와 반대로 간다.
- **잔여 리스크**: `response_format=json_object`(현재 무조건 실림) 호환은 문서상 "structured_outputs 지원"으로 추정할 뿐 확정이 아니다. → **실 API 스모크로 배포 전 확인**(quickstart §3). 실패 시 `visionChatOptions` 에서 responseFormat 을 조건부로 돌리고 파서의 코드펜스 제거 경로에 의존한다(파서는 이미 펜스를 벗긴다).
- **Alternatives considered**: `temperature` 유지 + 실패 시 롤백 — 부팅은 성공하고 첫 스캔에서 죽는 형태라 발견이 늦다. 기각.

## R3. 추론 토큰이 비용·지연에 미치는 영향

- **문제**: 추론 토큰은 출력 토큰으로 과금된다. 단가만 보면 출력 0.60→1.2 (2배)지만, 출력 **토큰 수** 자체가 늘어 스캔 1회 실비용은 2배를 넘을 수 있다. 지연도 같은 방향으로 늘고, vision 타임아웃은 60초·재시도 0이다.
- **Decision**: 코드로 방어하지 않고 **실측 후 판단**한다. 스모크 1회의 `promptTokens/completionTokens` 로그(이미 `logTokenUsage` 가 찍는다)와 응답 시간을 기록해 plan 의 수용 기준과 대조한다.
- **Rationale**: 추론 강도 노브(`reasoning_effort`)를 미리 `VisionProps` 에 추가하는 것은 필요가 확인되지 않은 설정이다. 실측이 허용 범위면 코드가 늘지 않는다.
- **수용 기준**: p50 응답 8초 이내(현행 대비 체감 악화 없음) & 스캔 1회 비용이 현행의 5배 이내. 초과 시 `VisionProps.reasoningEffort` 를 추가해 낮은 강도로 고정하는 후속 작업을 연다.
- **Alternatives considered**: 선제적으로 `reasoning-effort: low` 추가 — 파라미터 지원 여부가 미확인이라 R2 와 같은 실패 모드를 만든다. 기각.

## R4. "OCR 무시"를 어떻게 테스트하는가

- **문제**: OCR 무시는 코드 분기가 아니라 **프롬프트 문장**에 있다. "모델이 실제로 OCR 을 무시했다"는 실 모델 호출 없이는 단정할 수 없다.
- **Decision**: 두 층으로 나눠 검증한다.
  1. **프롬프트 계약 테스트**(결정적, CI): 가짜 `ChatModel` 로 `Prompt` 를 캡처해 — (a) OCR 목록이 **매칭 참조표로만** 제시되는지, (b) OCR 을 오탈자 교정 기준·메뉴 후보로 쓰라는 지시가 **없는지**, (c) 판독 근거를 사진으로 한정하는 지시가 있는지 검증한다. 기존 테스트가 이미 가짜 `ChatModel` 을 쓰므로 패턴 추가 비용이 없다.
  2. **서비스 계약 테스트**(결정적, CI): 클라이언트 OCR 의 `rawMenuName` 이 응답 어디에도 흘러들지 않음을 확인한다(`ScanService` 는 `idx` 만 읽는다 — 회귀 방지 고정).
  3. **정확도 판정**(수동, 배포 전 1회): 검증용 메뉴판 사진 표본에 대해 오염 OCR / 정확 OCR 두 요청의 결과가 같은지 육안 대조(quickstart §4).
- **Rationale**: 헌법 원칙 I 은 실패 테스트 선행을 요구하지만 모델 출력 자체는 결정적이지 않다. 프롬프트 계약은 우리가 소유한 유일한 결정적 표면이고, 지라 DoD 의 "OCR 무시 동작이 테스트로 검증된다"를 정직하게 만족시키는 형태다.
- **Alternatives considered**: 실 모델을 CI 에서 호출 — 비결정적·유료·느림. 기각(기존 `LlmSmokeTest` 와 동일하게 opt-in 수동 실행으로 분리).

## R5. seam 을 바꿔야 하는가

- **Decision**: `MenuBoardVisionExtractor.extract(imagePath, ocrItems)` 시그니처를 **바꾸지 않는다**.
- **Rationale**: OCR 목록은 판독에서 빠질 뿐 `matchedIdx` 산출에는 계속 필요하다(FR-004). 인자를 지우면 모든 `idx` 가 null 이 되어 클라이언트가 배지를 그릴 위치를 잃는다. 인자의 **의미**만 "판단 근거"에서 "매칭 참조표"로 바뀌며, 그 변화는 프롬프트 문장이 표현한다.
- **Alternatives considered**: `extract(imagePath)` + 별도 `match(menus, ocrItems)` 2단계 seam 분리 — 매칭을 모델 안에서 하는 현 구조상 호출이 2회로 늘어 비용·지연이 2배가 된다. 기각.

## R6. `idx` 중복 금지의 강제 지점

- **문제**: FR-005(한 `idx` 는 최대 하나의 결과)는 현재 **프롬프트 규칙으로만** 존재한다. `ScanService` 는 `idx` 가 요청 집합 안에 있는지만 거르고(`:55`) 중복은 통과시킨다. 모델이 바뀌는 시점에 프롬프트 준수만 믿는 것은 근거가 약하다.
- **Decision**: `ScanService` 에서 **먼저 나온 결과가 `idx` 를 갖고 이후 중복은 null 로 떨어뜨리는** 서버측 가드를 둔다.
- **Rationale**: 클라이언트는 `idx` 로 화면 박스를 찾는다. 중복이면 한 박스에 두 위험도가 겹쳐 그려지거나 뒤엣것이 앞엣것을 덮는다 — 사용자에게 보이는 손상이다. 기존 `takeIf { it in validIdxes }` 옆의 한 줄짜리 확장이고, 프롬프트가 지켜도 무해하다.
- **Alternatives considered**: 프롬프트 규칙만 유지 — 모델 교체와 동시에 검증 없는 신뢰를 남긴다. 기각. / 중복 시 전체 요청 실패 — 사용자 화면이 안 열린다. 기각.

## R8. 전 채팅 모델 luna 통일과 fan-out 폐기 (2026-08-11 범위 추가)

- **요청**: 모든 AI 모델을 luna 로 통일하고 Gemini·Upstage 관련 코드를 지운다.
- **문제**: 기피성분 판정(알러지·종교 — 안전 직결)은 서로 다른 3개 벤더를 병렬 호출해 `minAgreement=2` 로 합의를 요구했다. 전부 같은 모델이 되면 "서로 다른 모델의 합의"라는 안전 근거가 사라진다.
- **Decision**: luna 단일 호출로 축소하고 `LlmFanoutClient` 계열을 삭제한다(사용자 선택).
- **Rationale**: 배치 운영 설정이 이미 `min-agreement: 1` 이었다 — 교차검증은 코드에만 남아 있었고 실제로는 단일 모델 판단으로 돌고 있었다. 삭제는 새 위험을 만드는 게 아니라 실제 운영 상태를 코드로 확정하고, 쓰이지 않는 3벤더 배선·의존성(`google-genai`)·설정을 걷어낸다.
- **남는 안전장치**: 응답 계약 검증(후보 코드 밖 배제, 포함률 0~100, 맵기 0~10, 코드 중복 금지)은 그대로다. 위반 시 종합하지 않고 예외를 던져 배치가 그 음식을 미완료로 남긴다.
- **감수하는 비용**: 한 모델의 체계적 편향·환각을 다른 벤더가 잡아 주던 경로가 없다. 안전 직결 데이터라 검수 상태 구분(헌법 원칙 V)에 더 의존하게 된다.
- **Alternatives considered**: luna 3회 병렬 호출(self-consistency) — fan-out 구조를 살려 다수결을 유지하지만, 같은 모델·같은 프롬프트라 상관된 오류를 걸러내지 못하면서 비용만 3배다. 사용자가 기각.
- **범위 밖 유지**: `gpt-image-2`(이미지 생성)·Bedrock Titan(임베딩)은 채팅 모델이 아니라 luna 로 대체할 수 없다.

## R7. 단가 기본값의 조용한 불일치

- **문제**: `application.yml` 의 vision 블록에는 `pricing` 이 없어 `VisionProps` 기본값(gpt-4o-mini 0.15/0.60)이 적용된다. 모델만 갈아끼우면 새 모델 호출을 **옛 단가로** 기록한다.
- **Decision**: yml 에 `vision.pricing` 을 명시(0.2 / 1.2)하고, `VisionProps` 의 기본값·주석도 luna 기준으로 갱신한다.
- **Rationale**: yml 명시만으로 동작은 맞지만, 코드에 남은 옛 기본값은 다음 사람이 밟을 함정이다. 두 값이 어긋나지 않게 함께 옮긴다.
