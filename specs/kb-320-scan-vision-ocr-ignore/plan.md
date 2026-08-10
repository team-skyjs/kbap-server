# Implementation Plan: 스캔 비전 모델 교체 및 사진 단독 판독

**Branch**: `kb-320-scan-vision-ocr-ignore` | **Date**: 2026-08-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-320-scan-vision-ocr-ignore/spec.md`

## Summary

스캔 비전 판독의 진실 출처를 **사진 하나로 좁히고**, 모델을 `gpt-4o-mini` → `gpt-5.6-luna` 로 교체한다. 클라이언트 OCR 목록은 계속 받되 역할이 "오탈자 교정 기준·메뉴 후보 목록"에서 **"결과를 화면 박스에 잇는 매칭 참조표"** 로 축소된다.

기술적으로는 **프롬프트 문자열 + 설정값 변경**이고, 스키마·API 계약·모듈 구조·seam 시그니처는 건드리지 않는다. 검증은 (1) 가짜 `ChatModel` 로 프롬프트를 캡처하는 계약 테스트, (2) OCR 텍스트가 응답에 흘러들지 않음을 고정하는 서비스 테스트, (3) 실 API 스모크로 파라미터 호환·토큰·지연 실측(수동)의 3층이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0 (`spring-ai-starter-model-openai`, `OpenAiChatModel`)

**Storage**: 변경 없음. 스키마 마이그레이션 없음. 기존 `llm_call_cost` 기록 경로만 새 모델 이름·단가로 채워진다.

**Testing**: Kotest `BehaviorSpec` (given/`when`/then 한국어). 단위 = `:infra:llm` 가짜 `ChatModel`, 통합 = `:api` MockMvc + `FakeMenuBoardVisionExtractor`. 실 API 스모크는 `-Dllm.smoke.enabled=true` opt-in.

**Target Platform**: Linux 서버(`:api` bootJar), 운영 인스턴스 2대

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경은 `:infra:llm` + `:api` 두 모듈에 국한

**Performance Goals**: 스캔 응답 p50 8초 이내(현행 체감 유지). vision 타임아웃 60초·재시도 0 유지.

**Constraints**: 요청/응답 계약 불변(배포된 앱이 수정 없이 동작). 외부 LLM 호출은 트랜잭션 밖 유지. 스캔 1회 비용은 현행의 5배 이내(초과 시 추론 강도 조정 후속 작업).

**Scale/Scope**: 프로덕션 코드 3파일(프롬프트·설정 프로퍼티·서비스 가드) + 설정 1파일 + 테스트 2파일. 신규 클래스·신규 의존성·신규 엔드포인트 없음.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ PASS | 모든 task 가 실패 테스트 선행. 비결정적 표면(모델 출력)은 결정적 표면(프롬프트 문자열·서비스 매핑)으로 내려 테스트한다(research R4). 실 모델 호출은 CI 밖 opt-in 스모크로 분리해 Red/Green 판정을 오염시키지 않는다. |
| **II. Bounded Contexts** | ✅ PASS | 신규 도메인 의존 없음. 프롬프트는 `com.kbap.infra.llm.menu` 안에, 매칭 가드는 `com.kbap.api.scan` 안에 머문다. `ModuleBoundaryTest` 허용 맵 수정 불필요. |
| **III. Layered Dependency Direction** | ✅ PASS | seam `common.port.llm.MenuBoardVisionExtractor` 시그니처 불변(research R5). 구현은 `:infra:llm`, 조립은 `LlmConfiguration`, 소비는 `:api` — 방향 그대로. `:common` 은 손대지 않는다. |
| **IV. Persistence Ownership** | ✅ PASS | 엔티티·리포지토리·마이그레이션 변경 없음. 트랜잭션 경계 변경 없음(`scanMenuBoardImage` 의 의도적 무트랜잭션 유지). |
| **V. Domain Content Language Policy** | ✅ PASS | `koreanName`·`lang` 폴백 경로 불변. 프롬프트의 "koreanName 은 순수 한국어" 규칙(DB 조회 키)은 그대로 보존한다. |
| **Additional Constraints** | ✅ PASS | 외부 LLM 호출은 트랜잭션 밖 유지. 도메인/영속 모델을 응답으로 노출하지 않음(`ScanResponse` 경유) 유지. |

**게이트 결과**: 위반 없음. Complexity Tracking 비움.

**Phase 1 재점검**: 설계 산출물(contracts/·data-model.md) 확정 후 재평가 — 신규 클래스·모듈·의존이 생기지 않아 판정 불변. ✅ PASS 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-320-scan-vision-ocr-ignore/
├── plan.md              # 이 파일
├── spec.md              # /speckit-specify 산출물
├── research.md          # Phase 0 — R1~R7
├── data-model.md        # Phase 1 — 스키마 변경 없음의 근거 + 흐르는 값의 모양
├── quickstart.md        # Phase 1 — 로컬 실행·스모크·수동 정확도 검증 절차
├── contracts/
│   ├── scan-api.md      # 불변으로 지켜야 할 HTTP 계약(회귀 기준선)
│   └── vision-prompt.md # 이번에 바뀌는 프롬프트 계약(테스트가 assert 할 대상)
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출물 (이 명령이 만들지 않음)
```

### Source Code (repository root)

```text
infra/llm/src/main/kotlin/com/kbap/infra/llm/
├── menu/OpenAiMenuBoardVisionExtractor.kt   # [수정] SYSTEM_PROMPT · userPromptWith — 판독/매칭 분리
└── config/LlmModelProperties.kt             # [수정] VisionProps.pricing 기본값 0.2/1.2 + 주석

infra/llm/src/test/kotlin/com/kbap/infra/llm/
└── menu/OpenAiMenuBoardVisionExtractorTest.kt  # [수정] 프롬프트 계약 테스트 추가

api/src/main/kotlin/com/kbap/api/scan/
└── ScanService.kt                           # [수정] idx 중복 가드 (FR-005)

api/src/main/resources/
└── application.yml                          # [수정] vision.model · vision.pricing · temperature 제거

api/src/test/kotlin/com/kbap/api/scan/
└── ScanControllerTest.kt                    # [수정] OCR 텍스트 미유입 + idx 중복 가드 테스트 추가
```

**Structure Decision**: 기존 구조를 그대로 쓴다. 신규 파일 0개 — 이 기능은 "새 부품 추가"가 아니라 **기존 프롬프트의 판정 근거 축소 + 모델/단가 교체**이므로, 파일을 늘리면 변경 지점만 흩어진다. 프롬프트는 지금처럼 `OpenAiMenuBoardVisionExtractor` 의 코드 상수로 둔다(설정으로 빼지 않는다 — 운영자 편집 대상이 아니고, 프롬프트 계약 테스트가 코드 상수를 전제로 한다).

## 구현 순서 (Phase 2 미리보기 — tasks.md 가 확정)

1. **[Red]** 프롬프트 계약 테스트 — 캡처한 `Prompt` 가 contracts/vision-prompt.md 의 P1~P5 를 만족하는지. 현행 프롬프트에서 실패해야 한다.
2. **[Green]** `SYSTEM_PROMPT`·`userPromptWith` 재작성 — `[진실의 출처]` 절에서 OCR 교정 기준·환각 제외 지시를 걷어내고 `[매칭 — OCR 참조표]` 로 역할을 재정의.
3. **[Red→Green]** `ScanService` idx 중복 가드 + `ScanControllerTest` 중복 시나리오.
4. **[Red→Green]** `ScanControllerTest` — OCR `rawMenuName` 이 응답에 유입되지 않음 고정.
5. **[설정]** `application.yml` vision 블록: `model: gpt-5.6-luna`, `pricing` 명시(0.2/1.2), `temperature: 0.0` → `1.0`(research R2). `VisionProps` 기본 단가·주석 동기화.
6. **[검증]** `./gradlew build` 전체 통과 → 실 API 스모크(quickstart §3)로 파라미터 호환·토큰·지연·비용 실측 → 수동 정확도 대조(quickstart §4).

## Complexity Tracking

> Constitution Check 위반 없음 — 비움.
