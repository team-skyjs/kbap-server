# Implementation Plan: 배치 콘텐츠 4개 작업용 LLM 클라이언트 구현

**Branch**: `kb-224-llm-content-clients` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-224-llm-content-clients/spec.md`

## Summary

KB-182 가 `:core` 에 선언한 4개 배치 콘텐츠 seam(`FoodNameTranslationClient`·`FoodDescriptionClient`·`FoodImageGenerationClient`·`FoodAvoidanceAssessmentClient`)의 Spring AI 구현체를 `:infra:llm` 에 제공한다. 텍스트 작업(번역·설명)은 기존 OpenAI `LlmModelCaller` 빈 1건 호출 + JSON 파싱, 기피성분 조사는 기존 `LlmFanoutClient`(3개 모델 fan-out) 재사용 + 코드별 종합, 사진 생성은 Spring AI OpenAI 이미지 모델 + `StorageObjectStore` seam 업로드(put 확장)로 구현한다. 계약 DTO 의 init 불변식이 1차 검증이고, DTO 가 모르는 제약(code∈candidateCodes)만 구현체가 검증해 위반 시 예외를 전파한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1, Spring AI 2.0(`spring-ai-starter-model-openai`·`spring-ai-starter-model-google-genai` — 기존), AWS SDK S3(기존 `:infra:storage`), Jackson(kotlin module — 기존)

**Storage**: 영속(DB) 변경 없음. 오브젝트 스토리지(S3)에 생성 이미지 업로드 — `:core` `StorageObjectStore` seam 에 `put` 추가

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼. 외부 호출은 `LlmModelCaller`/`StorageObjectStore` 페이크로 대체(헌법 I — LlmFanoutClient 부분실패 페이크 검증 선례)

**Target Platform**: `:app:batch` bootJar (Linux server) — 구현 모듈은 `:infra:llm`(+`:core`·`:infra:storage` 소폭)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스의 인프라 어댑터 구현

**Performance Goals**: 해당 없음(배치 오프라인 처리 — 처리량 목표는 파이프라인 KB-182 소관)

**Constraints**: LLM 호출은 DB 트랜잭션 밖(기존 processor 구조가 보장), 모델 키 미구성 환경 부팅 안전(`@ConditionalOnProperty` 관례), 신규 외부 라이브러리 추가 없음

**Scale/Scope**: 구현체 4개 + 설정 확장 + 스토리지 seam 메서드 1개, 신규 파일 약 6–8개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 각 구현체의 프롬프트 조립·응답 파싱·계약 위반 처리를 BehaviorSpec 로 Red 먼저 작성. 외부 호출은 `LlmModelCaller`·`StorageObjectStore` 페이크 |
| II. Bounded Contexts | PASS | seam·계약 DTO 는 `:core` 소유(기존). 구현은 `:infra:llm` — 도메인 모듈 간 신규 의존 없음 |
| III. Layered Dependency Direction | PASS | `:infra:llm` → `:core` 만 의존(기존 그대로). 외부 시스템(LLM·S3)은 seam 인터페이스 경유. 조립은 부트앱(batch) config |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·스키마 변경 없음. `StorageObjectStore` 는 영속이 아니라 외부 시스템 seam |
| V. Domain Content Language Policy | PASS | 9개 대상 언어 전수 생성이 핵심 — `TargetLanguageTexts` init 이 컴파일·런타임 양쪽에서 강제. 안전 직결(기피성분)은 3모델 종합 |
| 추가 제약(트랜잭션 밖 LLM 호출) | PASS | `FoodContentItemProcessor` 가 무트랜잭션 구간에서 호출하고 진행 저장만 `REQUIRES_NEW` — 기존 구조 유지 |

**Post-Phase-1 재평가**: PASS 유지 — 설계 산출물이 신규 모듈·신규 도메인 의존·영속 변경을 도입하지 않음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-224-llm-content-clients/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── food-content-clients.md   # 4개 seam 계약 + 프롬프트/응답 JSON 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/storage/
└── StorageObjectStore.kt                  # put(path, bytes, contentType) 추가

infra/storage/src/main/kotlin/com/kbap/infra/storage/
└── S3StorageObjectStore.kt                # put 구현(PutObject)

infra/llm/src/main/kotlin/com/kbap/infra/llm/
├── config/
│   ├── LlmModelProperties.kt              # image 프로퍼티(kbap.llm.image.*) 추가
│   └── FoodContentClientConfiguration.kt  # 4개 구현체 빈 조립(@ConditionalOnProperty)
└── food/
    ├── SpringAiFoodNameTranslationClient.kt      # OpenAI caller 1건 호출 + 9언어 JSON 파싱
    ├── SpringAiFoodDescriptionClient.kt          # OpenAI caller 1건 호출 + 설명/번역/맵기 JSON 파싱
    ├── SpringAiFoodAvoidanceAssessmentClient.kt  # LlmFanoutClient 3모델 종합 + code 검증
    ├── OpenAiFoodImageGenerationClient.kt        # OpenAI 이미지 모델 + StorageObjectStore.put
    └── FoodContentJsonParser.kt                  # 코드펜스 제거 + jackson 파싱 공통(MenuBoardResultParser 선례)

infra/llm/src/test/kotlin/com/kbap/infra/llm/food/   # 각 구현체 BehaviorSpec(페이크 caller/store)
infra/storage/src/test/kotlin/com/kbap/infra/storage/ # put 테스트(기존 스타일)

app/batch/
├── build.gradle.kts                       # :infra:storage 의존 추가
└── src/main/kotlin/com/kbap/app/batch/config/
    └── BatchStorageConfig.kt              # StorageObjectStore 조립(api StorageConfig 선례)
```

**Structure Decision**: 구현체는 전부 `:infra:llm` 의 `food/` 하위 패키지에 응집한다(외부 시스템 구현은 `:infra:*`, seam 은 소비 계층/`:core` — CLAUDE.md 격리 패턴). 이미지 업로드는 기존 `:core` `StorageObjectStore` seam 을 확장해 재사용하고(`:infra:llm` 은 이미 `:core` 에 의존하므로 신규 모듈 의존 없음), S3 구현·빈 조립만 `:infra:storage`·`:app:batch` config 에 둔다. 배치 파이프라인(`FoodContentItemProcessor`)의 seam 소비 연결은 KB-183/184/209 소관 — 이 작업은 빈 제공까지다(FR-008: 계약 시그니처 무수정).

## Complexity Tracking

> 위반 없음 — 신규 모듈·신규 외부 의존·영속 변경 없이 기존 기틀(fan-out·storage seam·조건부 구성)을 재사용한다.
