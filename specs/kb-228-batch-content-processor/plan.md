# Implementation Plan: 배치 음식 콘텐츠 프로세서의 작업별 구현 — 이름 번역·설명 생성+번역·기피성분+맵기

**Branch**: `kb-228-batch-content-processor` | **Date**: 2026-07-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-228-batch-content-processor/spec.md`

## Summary

`FoodContentItemProcessor` 의 빈 스텁(설명·번역)을 채우고 콘텐츠 작업을 3그룹으로 재편한다:
① 이름 번역(`FoodNameTranslationClient`, 단일 모델) ② 설명 생성+설명 번역(`FoodDescriptionClient`, 단일 모델·한 호출) ③ 기피성분 조사+맵기 판정(`FoodAvoidanceAssessmentClient`, 3모델 fan-out 종합). 핵심 계약 변경은 **맵기(spiciness)를 설명 계약에서 기피성분 계약으로 이동** — 안전 직결 값이므로 단일 모델 판단을 금지하고 다중 모델 종합(유효 응답 2개 미만 시 판정 거부, 범위 밖 값 응답 무효)에 편입한다. 작업별 성공 결과는 기존 `REQUIRES_NEW` 진행 저장으로 즉시 커밋되어 부분 실패에도 보존된다. 이미지 생성은 범위 제외(스텁·조건 유지). DB 스키마 변경 없음.

**범위 경계 (LLM 클라이언트를 새로 만들지 않는다)**: 클라이언트 구현체 4종은 KB-224 에서 완성됐고 이번 작업은 이를 **재사용·연결**한다. 일감의 성격은 셋이다 — (1) 계약 정리: `FoodDescriptionContent` 의 spiciness 제거 + `FoodAvoidanceAssessmentClient` 반환 타입 변경(`:core`), (2) 계약 변경의 실행부: **기피성분 클라이언트에만** 맵기 판정 편입(프롬프트 루브릭 이동·응답 파싱 확장·무효 판정·평균 종합 — `:infra:llm` 중 유일한 로직 추가; 설명 클라이언트는 삭제만, 이름 번역 클라이언트는 무변경), (3) **본체**: 프로세서 빈 스텁을 채워 완성된 클라이언트들을 배치에 연결(`:app:batch` + `Food` 도메인 메서드).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1(Spring Batch — `:app:batch`), Spring AI 2.0(`:infra:llm` — OpenAI·Upstage·Gemini 3모델), Jackson(JSON 응답 파싱)

**Storage**: MySQL — `food` 테이블 기존 컬럼만 사용(`description`·`spiciness`·`name_translations`·`description_translations`·`avoidance_substances`·`content_status`). **Flyway 마이그레이션 없음**

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어) + JUnit 5 플랫폼. 단위(페이크 `LlmModelCaller`/클라이언트 페이크) + Spring 통합(`@SpringBootTest` + MySQL Testcontainers)

**Target Platform**: `:app:batch` bootJar(Linux 서버) — 컴포넌트 스캔 자신+`com.kbap.infra.llm`, flyway off

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 이번 변경 모듈: `:core`(계약) · `:infra:llm`(구현) · `:domain:food`(엔티티 도메인 메서드) · `:app:batch`(프로세서·조립)

**Performance Goals**: 배치 처리량은 LLM 응답 지연이 지배 — 청크 트랜잭션 없음(resourceless) 구조 유지로 DB 커넥션을 LLM 호출 동안 점유하지 않는다. 기피성분+맵기는 fan-out 1회로 함께 판정(추가 호출 0)

**Constraints**: LLM 키/활성 플래그 부재 시 batch 부팅이 실패하면 안 된다(기존 boot-safety 계약 — `LlmConfigurationBootSafetyTest`). 외부 LLM 호출은 DB 트랜잭션 밖. 처리 실패 음식은 skip+로그로 배치 전체를 중단시키지 않는다(기존 faultTolerant 유지)

**Scale/Scope**: 관리자 일괄 적재(KB-186)로 유입되는 INCOMPLETE 음식 수백~수천 건. 음식 1건당 최대 LLM 호출: 단일 2회 + fan-out 1회(3모델)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.* (헌법 v5.0.0)

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 계약 변경·프로세서 재편은 실패 테스트 선행(Red→Green→Refactor). 기존 테스트(`FoodContentDtoTest`·`SpringAiFood*ClientTest`·`FoodAvoidanceMapProcessorTest` 등)가 계약 변경의 Red 를 함께 구성 |
| II. Bounded Contexts | PASS | 도메인 간 신규 의존 없음. 기피성분 코드는 문자열 코드로만 참조(기존). 계약 DTO 는 `:core`(seam 소유 계층) |
| III. Layered Dependency Direction | PASS | seam 인터페이스는 `:core`, 구현은 `:infra:llm`, 소비는 `:app:batch` — 기존 방향 유지. 신규 모듈 의존 없음 |
| IV. Persistence Ownership | PASS | 콘텐츠 반영은 `Food` 엔티티 도메인 메서드(`updateNameTranslations`·`updateDescription`·`assessAvoidance`)가 소유. 트랜잭션 경계는 배치가 `TransactionTemplate(REQUIRES_NEW)` 로 명시 소유(기존 구조 유지) |
| V. Domain Content Language Policy | PASS | 9개 대상 언어 전수는 `TargetLanguageTexts` 불변식이 강제(부분 병합 없음). ko 는 원문. 안전 직결(기피성분·맵기)은 다중 모델 종합 — 검수 상태 구분은 KB-223(별도 스펙) 담당 |

**Post-Phase 1 재평가**: PASS — 설계 산출물(data-model·contracts)이 위 판정을 바꾸지 않음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-228-batch-content-processor/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── llm-clients.md   # Phase 1 output — 콘텐츠 클라이언트 seam 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/food/
├── FoodDescriptionClient.kt          # [변경] FoodDescriptionContent 에서 spiciness 제거
├── FoodAvoidanceAssessmentClient.kt  # [변경] 반환 타입 → FoodAvoidanceAssessmentResult(성분 목록 + spiciness)
├── FoodNameTranslationClient.kt      # 변경 없음
└── TargetLanguageTexts.kt            # [변경] byCode() 헬퍼 추가(LanguageCode → code 문자열 키)

domain/food/src/main/kotlin/com/kbap/domain/food/model/
└── Food.kt                           # [변경] updateNameTranslations · updateDescription ·
                                      #        assessAvoidance(substances, spiciness) · needsAvoidanceAssessment()

infra/llm/src/main/kotlin/com/kbap/infra/llm/food/
├── SpringAiFoodDescriptionClient.kt          # [변경] spiciness 프롬프트·파싱 제거(삭제만)
└── SpringAiFoodAvoidanceAssessmentClient.kt  # [변경] spiciness 판정 편입(응답 형식·검증·종합 — 유일한 로직 추가)

app/batch/src/main/kotlin/com/kbap/app/batch/content/
├── FoodContentItemProcessor.kt       # [변경] 3작업 그룹 구현(스텁 제거), 클라이언트 주입 — 본체
└── FoodContentBatchConfig.kt         # [변경] 프로세서 빈에 name·description 클라이언트 조립(ObjectProvider)

# 테스트 (동일 구조 미러링)
core/src/test/.../food/               # FoodContentDtoTest(계약 변경 반영)·FoodContentFakeTest
infra/llm/src/test/.../food/          # SpringAiFoodDescriptionClientTest·SpringAiFoodAvoidanceAssessmentClientTest
domain/food/src/test/.../model/       # Food 도메인 메서드 테스트
app/batch/src/test/.../content/       # FoodContentItemProcessor 3작업 테스트·잡 통합 테스트·BatchTestClientConfig
```

**Structure Decision**: 신규 모듈·패키지 없음 — 4개 기존 모듈의 기존 패키지 안에서 계약 변경·스텁 구현만 수행한다. 변경 파급은 `:core` 계약 → `:infra:llm` 구현 → `:app:batch` 소비 순으로 단방향이다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
