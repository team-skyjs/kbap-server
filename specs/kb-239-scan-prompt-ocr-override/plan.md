# Implementation Plan: 메뉴판 스캔 — 사진 판독 메뉴명이 OCR 텍스트를 덮어쓰도록 인식 지시 개선

**Branch**: `kb-239-scan-prompt-ocr-override` | **Date**: 2026-07-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-239-scan-prompt-ocr-override/spec.md`

## Summary

`OpenAiMenuBoardVisionExtractor` 가 vision 모델에 보내는 **프롬프트 문구만** 고친다. (1) 함께 넘기는 클라이언트 OCR 목록이 오타를 포함할 수 있는 메타정보임을 고지하고, (2) 사진 판독 결과와 OCR 텍스트가 다르면 **사진 판독을 최종 메뉴명으로 채택**하라는 규칙을 넣는다. `matchedIdx` 매칭·비메뉴 제외·가격 해석 규칙은 문구 그대로 유지한다. 요청/응답 DTO·seam 시그니처·엔티티·스키마 무변경 — 프로덕션 변경은 파일 1개(`OpenAiMenuBoardVisionExtractor.kt`)다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: 기존 `:infra:llm`(Spring AI `ChatModel`) 내부 변경만. 신규 의존 없음.

**Storage**: N/A — 엔티티·Flyway·저장 경로 무변경 (스캔 이력 기록은 기존 그대로)

**Testing**: 신규 테스트 없음 — 프롬프트 문자열 상수 변경이라 자동 검증할 로직 분기가 없다(research.md Decision 5). 기존 `OpenAiMenuBoardVisionExtractorTest`(비용 이벤트·파싱·실패 격리)를 회귀 가드로 무수정 통과시키고, 인식 품질(SC-001~SC-005)은 실사진 수동 확인으로 검증한다.

**Target Platform**: `:app:api` bootJar — `POST /api/v1/scans` 요청 경로에서 호출

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경은 `:infra:llm` 단일 모듈에 국한

**Performance Goals**: N/A — 호출 횟수·모델·토큰 구성 변화 없음(프롬프트 몇 줄 증가분 제외)

**Constraints**: API 계약 동결(FR-005) — 요청/응답 필드 0건 변경. 기존 규칙(비메뉴 제외·가격 해석·matchedIdx 의미)이 프롬프트 개편으로 흔들리지 않아야 한다.

**Scale/Scope**: 파일 2개(`OpenAiMenuBoardVisionExtractor.kt` 프롬프트 상수·유저 메시지 + 그 테스트). 좌표 기반 매칭 전환은 범위 밖(spec Assumptions).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | 해당 없음 (사용자 결정) | 변경 대상이 프롬프트 문자열 상수뿐이라 실패시킬 로직이 없다. 문자열 존재를 assert 하는 테스트는 이 변경이 노리는 결과(모델의 오타 교정)를 보장하지 못하므로 두지 않는다(research.md Decision 5). 기존 테스트는 회귀 가드로 무수정 통과하고, 검증은 실사진 수동 확인이 담당한다. |
| II. Bounded Contexts | PASS | 도메인 모듈 무접촉 — `:infra:llm` 어댑터 내부 문자열만 변경. `ScanService`·`FoodService` 등 호출 경로 불변. |
| III. Layered Dependency Direction | PASS | seam `MenuBoardVisionExtractor.extract(imagePath, ocrItems): List<ExtractedMenu>`(`:core`) 시그니처 불변. 의존 방향·모듈 그래프 변화 없음. |
| IV. Persistence Ownership | PASS | 영속 코드 무접촉 — 엔티티·리포지토리·트랜잭션 경계·Flyway 변경 없음. |
| V. Domain Content Language Policy | PASS | `lang` 처리·번역 폴백 경로 불변. `koreanName` 을 표준 한국어명으로 정제하는 기존 규칙도 문구 그대로 유지한다. |

**게이트 통과** — 위반 없음, Complexity Tracking 불필요.

*Post-Phase 1 재평가*: 설계 산출물(research/data-model/quickstart)이 신규 구조·신규 필드를 도입하지 않음을 확인 — 여전히 PASS.

## Project Structure

### Documentation (this feature)

```text
specs/kb-239-scan-prompt-ocr-override/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

`contracts/` 없음 — 외부 노출 계약(REST 요청/응답 DTO, seam 시그니처)이 **의도적으로 무변경**이다(FR-005). 계약 문서를 새로 만들 것이 없고, 기존 Swagger(`ScanApi`) 문구도 매칭 규칙이 그대로라 손대지 않는다.

### Source Code (repository root)

```text
infra/llm/
└── src/main/kotlin/com/kbap/infra/llm/menu/
    └── OpenAiMenuBoardVisionExtractor.kt        # SYSTEM_PROMPT 규칙 + userPromptWith 안내문 (유일한 변경 파일)
```

**Structure Decision**: 기존 구조 그대로 — 신규 파일·모듈·패키지 없음. 테스트 파일 포함 그 외 전부 무접촉이다(`MenuBoardResultParser`·DTO·컨트롤러·도메인 서비스).

## 핵심 설계 결정 (요약 — 상세는 research.md)

1. **규칙 본문은 SYSTEM_PROMPT 에, 유저 메시지는 안내문만 정렬**: 모델 규칙의 단일 출처를 시스템 프롬프트로 유지하고, 유저 메시지의 OCR 목록 소개 문장은 "참고용 메타정보"로 표현을 맞춘다(현재 문장은 OCR 을 권위처럼 읽히게 한다).
2. **`name` 의 출처를 사진으로 못 박는다**: `name` 정의("사진에 표기된 그대로")는 유지하되, "OCR 텍스트를 그대로 복사하지 말고, 사진 판독과 다르면 사진을 따른다"를 명시 규칙으로 추가한다.
3. **매칭 규칙은 건드리지 않는다**: `matchedIdx` 는 계속 OCR 항목을 가리키며, 텍스트가 깨졌으면 **사진 속 위치**로 판단한다는 기존 문장이 이 변경의 짝이다(오타 항목도 박스를 잃지 않는다).
4. **결과 기준은 여전히 사진**: "OCR 항목마다 결과를 만들지 않는다"(비메뉴 제외의 근거)를 규칙으로 남겨, 사진 우선 강조가 OCR 노이즈 유입으로 번지지 않게 한다.
5. **프롬프트 문구는 테스트하지 않는다**: 문자열 존재 assert 는 개선의 성패(모델의 오타 교정)와 무관하고 문구를 다듬을 때마다 같이 고쳐야 한다. 검증은 실사진 수동 확인이 담당한다(research.md Decision 5).

## Complexity Tracking

> 해당 없음 — Constitution Check 위반 없음.
