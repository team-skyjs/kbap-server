# Implementation Plan: 스캔 2.0 — 메뉴판 아닌 사진의 빈 결과 처리

**Branch**: `kb-330-scan-non-menu-board` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-330-scan-non-menu-board/spec.md`

## Summary

(2026-08-13 2차 개정 — 목표 확인: 클라이언트가 "메뉴판 아님"을 에러로 처리할 수 있어야 한다.)

스캔 2.0(서버 OCR)의 비전 시스템 프롬프트(`SERVER_OCR_SYSTEM_PROMPT`)에 환각 제외 규칙을 추가해 "메뉴판 아님 = 빈 추출"을 결정적 신호로 만들고, v2 경로에서 빈 추출을 **400 SCAN-003(`MENU_BOARD_NOT_DETECTED`)** 으로 변환한다. 클라이언트는 SCAN-003 으로 재촬영 안내를 분기하고, SCAN-002(503)는 시스템 장애 전용으로 남는다. 1.0 스캔은 무변경(빈 추출 → 200 빈 results).

변경: `SERVER_OCR_SYSTEM_PROMPT`(infra:llm) + `ErrorCode.MENU_BOARD_NOT_DETECTED`(common) + `ScanService.scan()` v2 분기 한 줄(api) + `ScanV2Api` 문서 + `ScanControllerTest` v2 케이스.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring AI 2.0 (`:infra:llm` — `OpenAiMenuBoardVisionExtractor`), vision 모델 `gpt-5.6-luna`

**Storage**: 변경 없음

**Testing**: Kotest BehaviorSpec — 서버 경로는 `FakeMenuBoardVisionExtractor` 기반 기존 통합 테스트 재사용, 프롬프트 실효성은 dev 실사진 수동 검증

**Target Platform**: `:infra:llm` 단일 파일 (`:api` 소비)

**Project Type**: 프롬프트 텍스트 수정 — 모듈 경계·API 계약 무변경

**Performance Goals**: 해당 없음 (프롬프트 길이 소폭 증가 — 토큰 비용 영향 미미)

**Constraints**: 1.0 프롬프트(`SYSTEM_PROMPT`)·파서·`ScanService` 는 건드리지 않는다. 형식 붕괴 → SCAN-002 폴백 경로 유지

**Scale/Scope**: 파일 1개(`OpenAiMenuBoardVisionExtractor.kt`)의 companion 프롬프트 문자열 수정

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS(조건부) | 결정적으로 테스트 가능한 서버 경로(빈 추출 → 200 빈 items)는 기존 Fake 기반 테스트가 이미 고정. LLM 출력 자체는 비결정적이라 프롬프트 실효성은 dev 실사진 검증으로 확인(research R3) |
| II. Bounded Contexts | PASS | 도메인 경계 무접촉 — infra 어댑터 내부 문자열 수정 |
| III. Layered Dependency Direction | PASS | seam(`MenuBoardVisionExtractor`) 계약 불변, 의존 방향 무변경 |
| IV. Persistence Ownership | PASS | 영속 무접촉 |
| V. Domain Content Language Policy | N/A | 음식 콘텐츠 번역·lang 정책 무관 |

**Post-Phase-1 재평가**: 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-330-scan-non-menu-board/
├── plan.md
├── research.md
├── data-model.md        # 변경 없음 명시
├── quickstart.md
├── contracts/
│   └── scan-v2-empty-result.md
└── tasks.md             # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/
└── OpenAiMenuBoardVisionExtractor.kt   # SERVER_OCR_SYSTEM_PROMPT 에 규칙 추가 (유일한 변경 파일)

infra/llm/src/test/kotlin/com/kbap/infra/llm/menu/
└── MenuBoardResultParserTest.kt        # 빈 배열 → 빈 목록 계약이 이미 고정돼 있는지 확인(없으면 케이스 추가)
```

**Structure Decision**: 변경은 `:infra:llm` 어댑터 내부 프롬프트 상수 하나다. `ScanService`·파서·API DTO·1.0 프롬프트는 무변경 — 빈 배열이 기존 경로로 흐르는 것이 곧 요구사항(1.0 동일 처리)이다.

## Complexity Tracking

위반 없음 — 해당 없음.
