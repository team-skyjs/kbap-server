# Implementation Plan: 스캔 2.0 — 메뉴판 아닌 사진의 빈 결과 처리

**Branch**: `kb-330-scan-non-menu-board` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-330-scan-non-menu-board/spec.md`

## Summary

스캔 2.0(서버 OCR)의 비전 시스템 프롬프트(`SERVER_OCR_SYSTEM_PROMPT`)에 1.0 의 환각 제외 규칙과 등가인 지시를 추가한다: 사진이 메뉴판으로 추정되지 않거나 메뉴를 확인할 수 없으면 빈 배열을 반환하고, 사진에서 읽히지 않는 메뉴를 지어내지 않는다. **서버 코드 변경 없음** — 빈 배열은 기존 파서·서비스 경로를 그대로 타고 200 + 빈 items 로 나가며, 이것이 1.0 의 주 경로와 동일한 처리다.

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
