# Tasks: 배치 음식 콘텐츠 프로세서의 작업별 구현 — 이름 번역·설명 생성+번역·기피성분+맵기

**Input**: Design documents from `/specs/kb-228-batch-content-processor/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/llm-clients.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 원칙 I) — 모든 스토리는 구현 전 실패 테스트(Red 확인) 필수. 계약 변경 태스크는 컴파일 실패도 Red 로 인정한다(타입이 계약이므로).

**Organization**: 유저 스토리별 페이즈 — 각 스토리는 독립 구현·독립 테스트 가능한 증분.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일·미완 태스크 의존 없음)
- **[Story]**: US1(기피성분+맵기) · US2(설명 생성+번역) · US3(이름 번역)

## Path Conventions

Gradle 멀티모듈 — 모듈 경로 그대로 사용(`core/`·`domain/food/`·`infra/llm/`·`app/batch/`). 테스트는 각 모듈 `src/test/kotlin/...` 미러링. 모듈 테스트 실행: `./gradlew :core:test :domain:food:test :infra:llm:test :app:batch:test`(통합은 Docker 필요).

---

## Phase 1: Setup

- [X] T001 변경 전 기준선 확인 — 워크트리 루트에서 `./gradlew build` 전체 그린 확인(이후 Red 가 이번 변경 때문임을 보장)

---

## Phase 2: Foundational (모든 스토리의 선행)

**목적**: `TargetLanguageTexts.byCode()` — US2·US3 프로세서 반영부가 공용으로 쓰는 키 변환(`LanguageCode` → code 문자열).

- [X] T002 Red: `core/src/test/kotlin/com/kbap/core/food/TargetLanguageTextsTest.kt` 에 `byCode()`(9개 언어 code 문자열 키 맵 반환) 실패 테스트 추가 후 `./gradlew :core:test` 로 Red 확인
- [X] T003 Green: `core/src/main/kotlin/com/kbap/core/food/TargetLanguageTexts.kt` 에 `byCode(): Map<String, String>` 구현 후 `:core:test` 그린 확인

**Checkpoint**: `:core` 그린 — 이후 모든 스토리 착수 가능.

---

## Phase 3: US1 — 기피성분 조사가 맵기까지 판정한다 (P1) 🎯 MVP

**Goal**: 맵기를 기피성분 계약으로 이동하고, 3모델 fan-out 종합에 맵기 판정을 편입해 프로세서 ③ 블록에서 성분 목록+맵기를 원자적으로 저장한다.

**Independent Test**: 맵기 -1·기피성분 null 음식 1건을 배치에 태우면 성분 목록과 맵기(0~10)가 함께 저장된다. 합의 미달(유효 응답 <2)이면 저장 없이 실패·skip 된다.

### Tests + Implementation (US1)

- [X] T004 [P] [US1] Red: `core/src/test/kotlin/com/kbap/core/food/FoodContentDtoTest.kt` 에 `FoodAvoidanceAssessmentResult` 검증(맵기 0..10 경계 — -1·11 거부, 0·10 허용) 실패 테스트 추가, `:core:test` Red 확인(신규 타입 미존재 → 컴파일 Red)
- [X] T005 [US1] Green: `core/src/main/kotlin/com/kbap/core/food/FoodAvoidanceAssessmentClient.kt` 에 `FoodAvoidanceAssessmentResult(substances: List<FoodAvoidanceAssessment>, spiciness: Int)` data class 신설(`init` 으로 0..10 강제 — 인터페이스 반환 변경은 아직 아님), `:core:test` 그린 확인
- [X] T006 [P] [US1] Red: `domain/food/src/test/kotlin/com/kbap/domain/food/model/FoodTest.kt`(기존 확장 또는 신규) — `assessAvoidance(substances, spiciness)` 원자 설정, `needsAvoidanceAssessment()` 트리거(성분 null ∨ 맵기 -1 → true, 둘 다 채워지면 false), 맵기 채워진 뒤 READY 전이 성립 실패 테스트 작성, `:domain:food:test` Red 확인
- [X] T007 [US1] Green: `domain/food/src/main/kotlin/com/kbap/domain/food/model/Food.kt` — `assessAvoidance` 시그니처 확장(spiciness 함께 설정)·`needsAvoidanceAssessment()` 추가, `:domain:food:test` 그린 확인
- [X] T008 [US1] Red: `infra/llm/src/test/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClientTest.kt` 확장 — 모델 응답 `{"assessments": [...], "spiciness": M}` 파싱, 맵기 범위 밖(11)·누락 응답 전체 무효(FR-004), 유효 응답 2개 미만 예외(FR-003), 맵기 평균 반올림 종합, `FoodAvoidanceAssessmentResult` 반환 실패 테스트 작성(새 반환 타입 사용 → 컴파일 Red), `:infra:llm:test` Red 확인
- [X] T009 [US1] Green: `core/.../FoodAvoidanceAssessmentClient.kt` 의 `call` 반환을 `FoodAvoidanceAssessmentResult` 로 변경 + `infra/llm/src/main/kotlin/com/kbap/infra/llm/food/SpringAiFoodAvoidanceAssessmentClient.kt` 에 맵기 편입 구현(프롬프트에 0~10 루브릭 이동·`AssessmentResponse` 에 spiciness 필드·무효 판정·평균 종합), `:infra:llm:test` 그린 확인
- [X] T010 [US1] 파급 정합(컴파일 복구): 반환 타입 변경 파급 수정 — `core/src/test/.../FoodContentFakeTest.kt`, `app/batch/src/test/kotlin/com/kbap/app/batch/BatchTestClientConfig.kt`, `app/batch/src/test/.../content/FoodAvoidanceMapProcessorTest.kt`, `app/batch/src/test/.../content/FoodContentJobTest.kt` 의 페이크 반환값과 `app/batch/src/main/.../content/FoodContentItemProcessor.kt` 의 `mapAvoidance` 를 새 타입에 맞춤(맵기 반영 로직은 T012 — 여기선 컴파일·기존 그린 유지만), `./gradlew build -Dkotest.tags="!arch"` 통과 확인
- [X] T011 [US1] Red: `app/batch/src/test/.../content/FoodAvoidanceMapProcessorTest.kt` 확장 — ③ 블록이 성분 목록과 맵기를 함께 저장, 트리거가 `needsAvoidanceAssessment()`(성분 있으나 맵기 -1 이어도 재판정), 후보 코드 빈 목록이면 미수행(맵기 -1 유지), 클라이언트 예외 시 기존 값 무훼손 실패 테스트 작성, `:app:batch:test` Red 확인
- [X] T012 [US1] Green: `app/batch/src/main/.../content/FoodContentItemProcessor.kt` ③ 블록 구현 — `needsAvoidanceAssessment()` 트리거·`assessAvoidance(result.substances → FoodAvoidanceItem 매핑, result.spiciness)` 반영·`saveProgress` 즉시 커밋, `:app:batch:test` 그린 확인
- [X] T013 [US1] 통합 검증: `app/batch/src/test/.../content/FoodContentJobTest.kt` 를 US1 수용 시나리오로 갱신(잡 실행 → 성분+맵기 함께 저장, 실패 음식 skip·잡 COMPLETED — SC-003·SC-004), `:app:batch:test` 그린 확인

**Checkpoint**: US1 단독 배포 가능 — 맵기·기피성분이 다중 모델 종합으로 채워진다(설명·이름 번역은 아직 스텁).

---

## Phase 4: US2 — 설명 생성이 설명 번역까지 함께 만든다 (P2)

**Goal**: 설명 계약에서 spiciness 를 제거(US1 로 이동 완료된 상태)하고, 프로세서 ② 블록에서 한 호출로 설명 원문+9개 언어 번역을 세트로 저장한다.

**Independent Test**: 설명이 플레이스홀더인 음식 1건을 배치에 태우면 설명(255자 이하)+번역 9개 언어가 함께 채워지고 맵기는 변경되지 않는다.

### Tests + Implementation (US2)

- [X] T014 [US2] Red: `core/src/test/.../FoodContentDtoTest.kt` 의 `FoodDescriptionContent` 케이스를 2필드(description·translations) 생성자로 고치고 spiciness 범위 케이스 삭제, `:core:test` Red 확인(3필드 생성자 → 컴파일 Red)
- [X] T015 [US2] Green: `core/src/main/.../FoodDescriptionClient.kt` 에서 `FoodDescriptionContent.spiciness`·`SPICINESS_RANGE` 제거 + 파급 삭제 — `infra/llm/src/main/.../food/SpringAiFoodDescriptionClient.kt` 프롬프트 맵기 항목·`DescriptionResponse.spiciness` 삭제(삭제만), `infra/llm/src/test/.../SpringAiFoodDescriptionClientTest.kt`·`core/src/test/.../FoodContentFakeTest.kt` 정합, `:core:test :infra:llm:test` 그린 확인
- [X] T016 [P] [US2] Red: `domain/food/src/test/.../model/FoodTest.kt` — `updateDescription(description, translations)` 원문+번역 세트 교체(부분 병합 없음) 실패 테스트, `:domain:food:test` Red 확인
- [X] T017 [US2] Green: `domain/food/src/main/.../model/Food.kt` 에 `updateDescription` 구현, `:domain:food:test` 그린 확인
- [X] T018 [US2] Red: `app/batch/src/test/.../content/FoodContentPipelineTest.kt` 또는 신규 프로세서 테스트 — ② 블록: 플레이스홀더 설명 → 설명+번역 9개 세트 저장·맵기 불변(US2 수용 2), 클라이언트 계약 위반 예외 시 기존 값 무훼손(FR-007), 클라이언트 미구성(null) 시 명시 예외 실패 테스트 작성, `:app:batch:test` Red 확인
- [X] T019 [US2] Green: `app/batch/src/main/.../content/FoodContentItemProcessor.kt` ② 블록 구현(`needsDescription() ∨ needsDescriptionTranslations()` → `updateDescription(..., translations.byCode())`·스텁 `generateDescription` 제거) + `FoodContentBatchConfig.kt` 에 `ObjectProvider<FoodDescriptionClient>` 조립(부팅 안전 — research R5) + `BatchTestClientConfig.kt` 에 설명 페이크 빈 추가, `:app:batch:test` 그린 확인

**Checkpoint**: US1+US2 — 설명·번역·기피성분·맵기 채워짐(이름 번역만 스텁).

---

## Phase 5: US3 — 이름 번역이 채워진다 (P3)

**Goal**: 프로세서 ① 블록에서 이름 번역 9개 언어를 채운다(독립 단일 호출 — 다른 작업 재호출 없음).

**Independent Test**: 이름 번역만 비어 있는 음식을 배치에 태우면 이름 번역만 수행되고(설명·기피성분 클라이언트 무호출) 9개 언어가 저장된다.

### Tests + Implementation (US3)

- [ ] T020 [P] [US3] Red: `domain/food/src/test/.../model/FoodTest.kt` — `updateNameTranslations(translations)` 전수 교체 실패 테스트, `:domain:food:test` Red 확인
- [ ] T021 [US3] Green: `domain/food/src/main/.../model/Food.kt` 에 `updateNameTranslations` 구현, `:domain:food:test` 그린 확인
- [ ] T022 [US3] Red: 배치 프로세서 테스트 — ① 블록: 이름 번역 미완이면 9개 전수 저장, 이름 번역만 미완인 음식은 다른 클라이언트 무호출(US3 수용 2 — 호출 카운터 페이크), 미구성(null) 시 명시 예외 실패 테스트 작성(`app/batch/src/test/.../content/`), `:app:batch:test` Red 확인
- [ ] T023 [US3] Green: `FoodContentItemProcessor.kt` ① 블록 구현(`needsNameTranslations()` → `updateNameTranslations(texts.byCode())`·스텁 `translateContent` 제거) + `FoodContentBatchConfig.kt` 에 `ObjectProvider<FoodNameTranslationClient>` 조립 + `BatchTestClientConfig.kt` 페이크 추가, `:app:batch:test` 그린 확인
- [ ] T024 [US3] 통합 검증: `FoodContentJobTest.kt` 전체 시나리오 — INCOMPLETE 음식 1건 → 잡 1회 실행으로 텍스트 전 항목(이름 번역·설명+번역·성분+맵기) 채움(SC-001), 이미지 미보유라 `content_status = INCOMPLETE` 유지(FR-008), `:app:batch:test` 그린 확인

**Checkpoint**: 3작업 전체 동작 — 스펙 SC-001·002 충족.

---

## Phase 6: Polish & Cross-Cutting

- [ ] T025 전체 검증: 워크트리 루트에서 `./gradlew build`(ArchUnit 포함) 전체 그린 확인 — 프로세서에 빈 스텁(`generateDescription`·`translateContent`)이 남아 있지 않고 이미지 블록·조립 주석은 유지됨을 함께 확인
- [ ] T026 문서·PR 정합: PR #90 본문의 "(예정)" 변경 사항 섹션을 실제 구현 내용으로 갱신하고, `specs/kb-228-batch-content-processor/quickstart.md` 수동 시나리오(LLM 키 보유 시)로 로컬 배치 1회 스모크(선택)

---

## Dependencies & Execution Order

- **Phase 1 → 2**: T001 기준선 후 T002~T003(byCode)
- **US1(P3 페이즈)**: Foundational 과 독립(byCode 미사용) — T001 직후 착수 가능. 내부 순서: (T004→T005) ∥ (T006→T007) → T008→T009→T010→T011→T012→T013
- **US2**: T003(byCode)·T010(파급 정합 — 계약 이동의 US1 측 완료) 이후. 내부: T014→T015, (T016→T017 은 T014 와 병렬 가능) → T018→T019
- **US3**: T003(byCode) 이후 — US1·US2 와 논리 독립이나 `FoodContentItemProcessor.kt`·`FoodContentBatchConfig.kt` 를 공유하므로 순차 권장. 내부: (T020→T021) → T022→T023→T024
- **Polish**: 전 스토리 완료 후 T025→T026

### Parallel Opportunities

- US1 내: **T004(core Red) ∥ T006(domain Red)** — 다른 모듈·무의존. T005 ∥ T007 도 동일.
- US2 내: **T016(domain Red) ∥ T014(core Red)** — 다른 모듈.
- US3 내: **T020(domain Red)** 은 US2 진행 중에도 착수 가능(다른 파일).
- 프로세서·조립 파일(`FoodContentItemProcessor.kt`·`FoodContentBatchConfig.kt`·`BatchTestClientConfig.kt`)을 만지는 태스크(T010·T012·T019·T023)는 병렬 금지(같은 파일).

## Implementation Strategy

**MVP = US1**(Phase 1 + Phase 3): 맵기 계약 이동과 다중 모델 종합이 이번 재편의 핵심 계약 변경 — 이것만으로 안전 직결 데이터 파이프라인이 완성되고 배포 가능하다. 이후 US2(설명 계약 확정+② 구현) → US3(① 구현) 순 증분 전달. 각 체크포인트에서 스토리 독립 테스트 기준으로 검증하고 작업/논리 단위마다 커밋한다(헌법 Development Workflow).
