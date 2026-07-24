# Implementation Plan: 배치 콘텐츠 4작업 LLM 호출 인터페이스 사전 선언

**Branch**: `kb-182-llm-client-interface` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-182-llm-client-interface/spec.md`

## Summary

KB-182 배치 골격의 콘텐츠 4작업(이름 번역·설명 생성·사진 생성·기피성분 매핑)이 사용할 외부 LLM 호출 계약을 **`:core` seam 인터페이스 4개 + DB 적재 형태와 1:1 대응하는 DTO** 로 선언한다. 구현은 후속 태스크(KB-183·184·209)가 `:infra:llm`(사진은 Lambda 경유 가능)에서 제공한다. 이번 범위는 선언·DTO 불변 검증까지 — Spring 배선 없음, 부팅·빌드 그린 유지.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: 없음(신규 의존 0) — `:core` 는 Spring-free, 선언만 추가

**Storage**: N/A — DTO 가 `food` 테이블 컬럼 형태(번역 JSON 맵·spiciness·image_ref·avoidance_substances JSON)와 대응하지만 이번 범위에 영속 코드 없음

**Testing**: Kotest(JUnit5 러너) — `core/src/test` 에 DTO 불변(init require) 단위 테스트

**Target Platform**: JVM 서버(멀티모듈 모놀리스 내 `:core`)

**Project Type**: 멀티모듈 백엔드 — 공유 커널 seam 선언

**Performance Goals**: N/A(선언만 — 런타임 코드 경로 없음)

**Constraints**: `:core` 는 Spring-free 유지. `:infra:llm` 은 `:core` 만 의존하므로 계약은 도메인 모듈 타입을 참조할 수 없다(candidateCodes 는 `String` 코드로 수령).

**Scale/Scope**: 인터페이스 4개 + DTO 3~4개 + 불변 테스트 1~2 스펙 파일. 프로덕션 로직 없음.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 유일한 로직은 DTO init 불변(9언어 전수·범위 검증) — 불변 테스트를 먼저 작성(Red)하고 DTO 선언으로 통과(Green). 인터페이스 선언 자체는 로직이 없어 테스트 대상 아님. |
| II. Bounded Contexts | PASS | 계약·DTO 는 `:core`(공유 커널)에만 추가. 도메인 모듈 타입(`FoodAvoidanceItem`·`AvoidanceSubstanceCode`) import 없음 — 성분 참조는 코드 문자열. |
| III. Layered Dependency | PASS | 이 작업이 원칙 III 의 "외부 시스템 클라이언트는 port 인터페이스(seam)로만" 조항의 이행이다. 기존 seam(`MenuBoardVisionExtractor`·`StorageObjectStore`)과 동일 관례. 의존 방향 변화 없음. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리 무접촉. |
| V. Language Policy | PASS | 번역 DTO 가 ko 원문 + 9개 대상 언어 전수를 계약 수준(`LanguageCode` 키 + init 검증)에서 강제 — 헌법 V 의 사전 번역 정책과 일치. |

**Post-Phase 1 재평가**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-182-llm-client-interface/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── food-content-contracts.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/food/          # 신규 패키지 — 음식 콘텐츠 생성 seam (Food{X}Client 통일)
├── FoodNameTranslationClient.kt                           # 음식명 번역 계약
├── FoodDescriptionClient.kt                    # 설명+맵기 생성 계약 (+FoodDescriptionContent DTO 동거)
├── FoodImageGenerationClient.kt                          # 사진 생성 계약(키 지정 입력 → 저장 완료 키 반환)
├── FoodAvoidanceAssessmentClient.kt                      # 기피성분 조사 계약 — 3-API 종합 (+FoodAvoidanceAssessment DTO 동거)
└── TargetLanguageTexts.kt                         # 9개 대상 언어 전수 번역 맵 DTO(이름·설명 공용)

core/src/test/kotlin/com/kbap/core/food/
├── TargetLanguageTextsTest.kt                     # 9언어 전수·blank 불변 (Red 선행)
└── FoodContentDtoTest.kt                          # FoodDescriptionContent·FoodAvoidanceAssessment 불변
```

**Structure Decision**: 기존 seam 관례를 그대로 답습한다 — 인터페이스와 그 전용 DTO 를 같은 파일에 동거(`MenuBoardVisionExtractor` 패턴), 컨텍스트별 패키지(`core/scan`·`core/storage` 처럼 `core/food` 신설). `:infra:llm` 이 `:core` 만 의존하므로 구현 가능 위치가 자동 보장되고, `:app:batch` 는 이미 `:core` 를 전이 의존한다.

## Complexity Tracking

위반 없음 — 기재 사항 없음.
