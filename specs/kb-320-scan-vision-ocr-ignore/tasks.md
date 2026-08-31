---

description: "Task list for kb-320 스캔 비전 모델 교체 및 사진 단독 판독"
---

# Tasks: 스캔 비전 모델 교체 및 사진 단독 판독

**Input**: Design documents from `/specs/kb-320-scan-vision-ocr-ignore/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 구현 전 실패 테스트를 작성하고 Red 를 눈으로 확인한다.

**Organization**: 스토리별 그룹. US1·US2·US3 는 P1(같은 릴리스에서 함께 성립해야 함), US4 는 P2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 task 에 의존 없음)
- 모든 경로는 저장소 루트 기준

## 이 기능의 성격 (task 실행 전 필독)

프로덕션 변경은 **4파일뿐**이고 신규 클래스는 0개다. 대부분의 작업은 "프롬프트 문장 재작성"과 "설정값 교체"이며, 테스트는 **모델 출력이 아니라 모델에게 보낸 프롬프트**를 검증한다(research R4). 새 파일을 만들고 싶어지면 잘못 가고 있는 것이다.

**절대 하지 말 것**: `ScanRequest`/`ScanResponse` 필드 변경, seam `MenuBoardVisionExtractor` 시그니처 변경, `ScanControllerTest` 의 **기존** given/when/then 기대값 수정(contracts/scan-api.md — 이 파일에 허용되는 편집은 테스트 추가뿐).

---

## Phase 1: Setup (기준선 확보)

**Purpose**: 변경 전 상태를 고정해 이후 Red/Green 판정이 신뢰 가능하게 만든다

- [x] T001 `./gradlew :infra:llm:test :api:test --tests "com.kbap.api.scan.*"` 를 실행해 **변경 전 전부 통과**함을 확인하고 결과를 메모한다 (이후 실패는 전부 이번 변경 탓임을 보장)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 프롬프트 계약 테스트가 성립하려면 `Prompt` 를 캡처할 수단이 먼저 있어야 한다. US1·US2 가 모두 여기에 의존한다.

**⚠️ CRITICAL**: T002 없이는 US1 의 어떤 테스트도 쓸 수 없다

- [x] T002 `infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt` 에 **프롬프트 캡처 헬퍼**를 추가한다 — 기존 `chatModelReturning` 옆에 호출된 `Prompt` 를 `MutableList` 에 기록하고 정상 응답을 돌려주는 `chatModelCapturing(captured: MutableList<Prompt>, response: ChatResponse): ChatModel` 을 둔다. 프롬프트 전문은 `prompt.instructions.joinToString("\n") { it.text }` 로 얻는다

**Checkpoint**: 프롬프트를 문자열로 꺼내 assert 할 수 있다 — US1·US2 시작 가능

---

## Phase 3~5: 프롬프트 사진 단독화 + 헤더 버저닝 — **철회** (2026-08-11)

KB-319(PR #141)가 같은 목표를 서버 OCR 로 먼저 구현·머지한 사실이 확인돼 이 구간의 산출물을 전부 되돌렸다.
근거는 research R9·R10, 대체 설계는 Phase 9.

- [~] T003~T011 프롬프트 계약 테스트(P1~P7) — 프롬프트를 바꾸지 않으므로 철회
- [~] T007~T009 `PHOTO_ONLY_SYSTEM_PROMPT` 재작성 — 철회. develop 의 `SERVER_OCR_SYSTEM_PROMPT` 가 대체
- [~] `MenuBoardReadingMode` seam 인자 · `X-API-Version 2026.08.08` 게이트 — 철회
- [x] T012~T014 `idx` 중복 서버 가드 + 테스트 — **유지**(v1 전용, Phase 9 에서 재적용)
- [x] T015~T017 v1 계약 회귀 증명 — **유지**

## Phase 6: User Story 4 - 스캔 비용이 계속 집계된다 (Priority: P2)

**Goal**: 모델을 `gpt-5.6-luna` 로 교체하고 새 단가로 비용이 기록된다.

**Independent Test**: 설정 반영 후 스모크 1회의 `llm_call_cost` 행이 새 모델 이름 + 새 단가 기준 금액을 갖는다.

**의존**: 없음 — 편집 파일(`application.yml`·`LlmModelProperties.kt`)이 US1~US3 과 겹치지 않아 **전 구간 병렬 가능**.

### Tests for User Story 4 (REQUIRED — 먼저 쓰고 FAIL 확인) ⚠️

- [x] T018 [P] [US4] `infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractorTest.kt` 의 단가 상수를 새 단가(입력 0.2 / 출력 1.2)로 바꾼 **비용 산정 테스트**를 추가한다 — 기존 `1237 promptTokens / 567 completionTokens` 케이스는 그대로 두고(옛 단가 산식 회귀), 새 단가 `LlmPricing(0.2, 1.2, 1500.0)` 로 기대 `costUsd`·`costKrw` 를 계산해 검증하는 케이스를 하나 더 둔다

### Implementation for User Story 4

- [x] T019 [P] [US4] `api/src/main/resources/application.yml` 의 `kbap.llm.vision` 블록 수정 — `model: gpt-4o-mini` → `model: gpt-5.6-luna`, `temperature: 0.0` → `temperature: 1.0`, 그리고 `pricing:` 블록 신설(`input-usd-per-million-tokens: 0.2` / `output-usd-per-million-tokens: 1.2`). 블록 위 주석의 `gpt-4o-mini vision` 언급도 함께 갱신한다
- [x] T020 [P] [US4] `infra/llm/src/main/kotlin/com/kbap/infra/llm/config/LlmModelProperties.kt` 의 `VisionProps.pricing` 기본값을 `0.2` / `1.2` 로 바꾸고 주석 `// gpt-4o-mini 기본 단가` 를 새 모델 기준으로 고친다 (yml 이 명시하므로 동작엔 무영향 — 다음 사람이 밟을 함정 제거, research R7)
- [x] T021 [US4] `./gradlew :infra:llm:test :api:test` 로 T018 Green + `LlmConfigurationBootSafetyTest`·`LlmConfigurationOptionsTest` 등 기존 설정 테스트가 새 모델명으로도 통과하는지 확인

**Checkpoint**: 새 모델·새 단가가 설정에 반영됐고 비용 산식이 검증됐다

---

## Phase 7: Polish & 배포 전 실측

**Purpose**: 결정적 검증으로 닫을 수 없는 항목(파라미터 호환·비용·지연·정확도)을 실측으로 닫는다

- [x] T022 `./gradlew build` 전체 통과 확인 (MySQL Testcontainers — Docker 필요)
- [ ] T023 **실 API 스모크**(v1·v2 양쪽) — quickstart.md §3 절차대로 실 키로 스캔 1회 호출. `temperature: 1.0` 수용 여부, `response_format=json_object` 호환, `completionTokens`(추론 토큰 포함), 왕복 지연, `costUsd`/`costKrw`, `llm_call_cost.model_name` 을 기록한다. **200 이 아니거나 파싱 실패면 여기서 멈추고** research R2 의 폴백(responseFormat 조건부 해제)을 검토한다
- [ ] T024 T023 결과를 plan.md 의 수용 기준과 대조 — p50 8초 이내 & 스캔 1회 비용이 현행의 5배 이내. **초과하면 머지하지 않고** `VisionProps.reasoningEffort` 추가를 후속 작업으로 연다(research R3)
- [ ] T025 **수동 정확도 대조** — quickstart.md §4 절차대로 메뉴판 사진 3~5장에 정확 OCR(A)/오염 OCR(B) 두 요청을 보내고 표를 채운다. SC-001(A·B 결과 동일)·SC-002(누락 메뉴 포함)·FR-003(가짜 항목 배제)·SC-003(오탈자 교정)·SC-004(idx 중복 0) 판정
- [ ] T026 T025 의 표를 PR 본문에 붙인다 — 자동화 회귀 스위트가 없어 이것이 유일한 정확도 근거다
- [ ] T027 [P] `../kbap-agenthub/wiki/` 에 "스캔 판독은 사진 단독, OCR 은 매칭 참조표" 결정을 한 항목으로 기록하고 `INDEX.md` 에 한 줄 추가 (코드만 봐선 알 수 없는 설계 제약 — CLAUDE.md 지식 위키 규칙)

---

## Phase 8: 전 채팅 모델 luna 통일 + 다중 벤더 제거 (2026-08-11 범위 추가)

**요청**: "모든 ai 모델은 luna로 통일. 제미나이·업스테이지 안 쓰니 관련 코드도 지워도 좋다."
**결정**: 기피성분 판정의 3모델 교차검증(`minAgreement=2`)을 포기하고 **luna 단일 호출 + fan-out 삭제**(사용자 선택).
**근거 보강**: 배치 설정은 이미 `min-agreement: 1` 로 운영 중이었다 — 교차검증은 실질적으로 이미 꺼져 있었고, 이번 변경이 그 상태를 코드로 확정한다.

- [x] T028 `SpringAiFoodAvoidanceAssessmentClient` 를 단일 caller 로 재작성 — 응답이 계약(후보 코드·포함률 0~100·맵기 0~10·코드 중복 금지)을 어기면 `IllegalArgumentException`. `SpringAiFoodAvoidanceAssessmentClientTest` 를 다중모델 종합 12케이스 → 단일응답 검증 12케이스로 교체
- [x] T029 fan-out 계열 삭제 — `client/LlmFanoutClient.kt`, `model/{LlmFanoutResult,LlmChatResult,LlmModelFailure,LlmModelId}.kt`, `client/LlmFanoutClientTest.kt`
- [x] T030 `LlmModelCaller` 에서 `modelId` 제거, `SpringAiModelCaller` 는 로깅용 `modelName: String` 을 받는다
- [x] T031 `LlmConfiguration` 정리 — `upstageModelCaller`·`geminiModelCaller`·`llmFanoutClient`·`llmFanoutExecutor`·`geminiChatModel`·`geminiChatOptions`·`DEFAULT_UPSTAGE_BASE_URL`·`com.google.genai` import 삭제. `openAiChatOptions` 는 모델 분기 없이 항상 `maxCompletionTokens`+`reasoningEffort`, `requireOpenAiApiKey` 는 `LlmModelId` 대신 프로퍼티 경로 문자열을 받는다
- [x] T032 `FoodContentClientConfiguration.foodAvoidanceAssessmentClient` 를 `avoidanceOpenAiModelCaller` 주입으로 바꾸고 `@ConditionalOnProperty(kbap.llm.openai.enabled)` 추가 — caller 없이 조립되지 않게
- [x] T033 `LlmModelProperties` 에서 `upstage`·`gemini`·`AvoidanceProps.minAgreement` 제거
- [x] T034 설정 테스트 갱신 — `LlmConfigurationOptionsTest`(upstage/gemini 케이스 제거), `LlmConfigurationBootSafetyTest`(fan-out 빈 검증 → 조건부 조립·키 누락 fail-fast 검증), `LlmConfigurationApiKeyTest`(프로퍼티 경로 기반), `LlmConfigurationBaseUrlTest`, `LlmSmokeTest`(3모델 fan-out → luna 단일 호출)
- [x] T035 빌드 의존성 제거 — `infra/llm/build.gradle.kts` 의 `spring-ai-starter-google-genai`, `gradle/libs.versions.toml` 항목
- [x] T036 설정 파일 정리 — api yml 의 `kbap.llm.upstage` 블록 삭제(미사용 빈 + 부팅 시 `UPSTAGE_API_KEY` 강제였다), batch yml 의 `upstage`·`gemini` 블록 삭제 및 `openai.model` → `gpt-5.6-luna`(단가 0.2/1.2, max-output-tokens 4096)
- [x] T037 배포 설정 정리 — `.env.example`·`docker-compose.prod.yml` 에서 `UPSTAGE_API_KEY`·`GOOGLE_API_KEY` 제거하고 실제 부팅 필수 키인 `OPENAI_API_KEY`·`IMAGE_PUBLIC_BASE_URL` 로 교체
- [x] T038 `CLAUDE.md` 기술 스택의 LLM 문단을 실제 구조(OpenAI 단일 벤더·fan-out 없음)로 갱신
- [x] T039 `./gradlew build` 전체 통과 확인

**Checkpoint**: 채팅·비전이 `gpt-5.6-luna` 단일 벤더로 통일되고 Upstage·Gemini·fan-out 코드가 사라졌다. 이미지 생성(`gpt-image-2`)·임베딩(Bedrock Titan)은 채팅 모델이 아니라 유지.

---

## Phase 9: 스캔 v1/v2 경로 분리 (2026-08-11 최종 설계)

**대체 대상**: Phase 3~5 의 헤더 버저닝. **근거**: research R9.

- [x] T040 develop 병합 — KB-319 반영. 충돌 6파일은 develop 쪽 채택 후 이 브랜치 고유분만 재적용
- [x] T041 v1 복원 — `ScanController` 헤더 분기 제거, `ScanRequest.items` 를 `@NotEmpty` 필수로 되돌림, `ScanResponse` 에서 `similarFood` 제거
- [x] T042 v1 swagger 복원 — `ScanApi` 에서 `X-API-Version` 파라미터·계약 버전 분기 설명·서버 OCR 예시 제거
- [x] T043 v2 신설 — `ScanV2Controller`(`ApiPaths.V2 + "/scans"`)·`ScanV2Api`·`ScanV2Request`(imagePath 만)·`ScanV2Response`(idx 없음, similarFood 있음)
- [x] T044 `ScanService` 진입점 분리 — `scanMenuBoardImage`(v1) / `scanMenuBoardImageV2`(v2, `ocrItems=emptyList()`·`similarFoodFallback=true`), 내부 `scan()` 공유
- [x] T045 `WebConfig` 보호 경로에 `/api/v2/scans` 등록 — **누락 시 v2 전 시나리오가 401 로 실패**(실제로 밟음)
- [x] T046 테스트 이관 — KB-319 의 헤더 시나리오 8건을 v2 경로로, 버전 폴백 시나리오 2건은 경로 분리로 의미가 사라져 제거
- [x] T047 v1 회귀 테스트 재추가 — `idx` 중복 가드·OCR 텍스트 미유입 2건
- [x] T048 `./gradlew build` 전 모듈 그린

**Checkpoint**: 계약을 경로가 결정한다. 스캔에서 `X-API-Version` 참조가 0건이다.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 의존 없음
- **Phase 2 (Foundational)**: T001 이후. **US1·US2 의 프롬프트 테스트를 블록**한다
- **Phase 3~6 (User Stories)**: T002 이후. US1↔US2 는 파일이 겹치는 구간(프롬프트 테스트)만 순차, 나머지는 병렬. US3·US4 는 전 구간 병렬
- **Phase 7 (Polish)**: US1~US4 전부 완료 후

### 파일 충돌 맵 (병렬 판단 근거)

| 파일 | 건드리는 task |
|------|---------------|
| `OpenAiMenuBoardVisionExtractorTest.kt` | T002, T003~T006, T011, T018 → **전부 순차** |
| `OpenAiMenuBoardVisionExtractor.kt` | T007~T009 → 순차 |
| `ScanService.kt` | T013 |
| `ScanControllerTest.kt` | T012, T015 → 순차 |
| `application.yml` | T019 |
| `LlmModelProperties.kt` | T020 |

### Parallel Opportunities

```bash
# T002 완료 후 세 갈래를 동시에 진행할 수 있다:
# 갈래 A (US1+US2 프롬프트): T003 → T004 → T005 → T006 → T011 → [Red 확인] → T007 → T008 → T009 → T010
# 갈래 B (US2+US3 서비스):   T012 → T015 → [Red 확인] → T013 → T014 → T016 → T017
# 갈래 C (US4 설정):         T018 → [Red 확인] → T019 → T020 → T021
```

갈래 A 와 C 는 `OpenAiMenuBoardVisionExtractorTest.kt` 를 공유하므로, 한 사람이 작업한다면 T018 을 T003~T006·T011 과 함께 몰아 쓰고 Red 를 한 번에 확인하는 편이 빠르다.

---

## Implementation Strategy

### MVP (US1 단독으로는 배포 불가)

이 기능은 US1·US2·US3 가 **같은 릴리스에서 함께 성립해야** 한다 — US1 만 넣으면 판독은 좋아지지만 `idx` 중복 방어가 없고(US2), 계약 회귀 증명이 없다(US3). 따라서 최소 배포 단위는 **Phase 1~5 전체**다.

US4(P2)는 단독 분리가 가능하지만, 모델 교체 없이 프롬프트만 바꾸면 지라 DoD 의 절반만 채워진다 — 함께 간다.

### 권장 순서 (1인 작업)

1. T001 → T002
2. T003~T006 + T011 + T018 몰아 쓰기 → `./gradlew :infra:llm:test` 로 **Red 한 번에 확인**
3. T007~T010 (프롬프트 재작성) → Green
4. T012 + T015 → Red → T013 → T014 → Green
5. T016~T017 (계약 회귀 증명)
6. T019~T021 (설정)
7. T022 → T023 → T024 → T025 → T026 → T027

### Commit 단위

- T002 (테스트 인프라)
- T003~T011 (실패 테스트 — Red 커밋)
- T007~T010 (프롬프트 — Green)
- T012~T014 (idx 가드)
- T019~T021 (모델·단가 설정)
- T027 (위키)

---

## Notes

- 프롬프트 문자열 assert 는 **전문 일치가 아니라 핵심 문구 포함/미포함**으로 쓴다. 전문 일치로 쓰면 문장을 다듬을 때마다 테스트가 깨져 아무도 손대지 않게 된다
- Kotlin 소스 주석 금지 규약 유지 — 프롬프트 절 재작성은 프롬프트 **문자열 안**에서 하고, 코드에 "왜 바꿨는지" 주석을 달지 않는다(근거는 이 문서와 커밋 메시지)
- `ScanService.kt:34-35` 의 주석 처리된 소유권 검증과 짝인 비활성 테스트 2건(`xwhen`)은 이번 범위 밖 — 그대로 둔다
- Red 확인은 "테스트를 썼다"가 아니라 **실행해서 빨간 것을 봤다**를 의미한다(헌법 원칙 I)
