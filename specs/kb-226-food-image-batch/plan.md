# Implementation Plan: OpenAI Batch API 기반 음식 이미지 비동기 생성

**Branch**: `kb-226-food-image-batch` | **Date**: 2026-07-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-226-food-image-batch/spec.md`

**설계 논의 기록**: https://claude.ai/code/artifact/e1e9918a-33cd-43ea-ae0c-a407562e7be6 (상태 다이어그램·수렴 전이표·시퀀스)

## Summary

이미지 생성을 스프링 배치에서 완전히 분리한다. 관리자가 이미지 없는 음식을 OpenAI Batch API에 일괄 제출(응답 즉시)하고, api 서버가 1시간 주기 `@Scheduled` + ShedLock(JDBC)으로 완료 배치를 회수해 S3에 저장하고 `food.imageRef`를 갱신한다. 상태의 원천은 우리 메타 테이블(`image_batch`·`image_batch_item`)이며, 제출 재실행은 빠진 것만 다시 제출하는 멱등 구조다. 음식 상태에 `TEXT_READY`(텍스트 완료·이미지 대기)를 신설하고, 전이는 칼럼 상태로 목표 상태를 계산하는 수렴 함수 하나로 통일해 텍스트/이미지 어느 쪽이 먼저 끝나도 안전하다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1

**Primary Dependencies**: Spring Web(admin API·스케줄링), Spring Data JPA, Flyway, AWS SDK S3(기존 `S3StorageObjectStore`), OpenAI Batch·Files API(REST 직접 호출 — Spring AI 2.0 미지원 영역), **신규: ShedLock(shedlock-spring + shedlock-provider-jdbc-template)**

**Storage**: MySQL(메타 테이블 `image_batch`·`image_batch_item`, `shedlock`, `food.content_status` ENUM 확장) + S3(이미지 원본)

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + 페이크(OpenAI 배치 클라이언트·`StorageObjectStore`), MySQL Testcontainers 통합 테스트

**Target Platform**: 운영 api 2대(스케줄 중복 실행 방지 필수 — ShedLock), `:app:batch`는 텍스트 3작업 전담으로 축소

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스(기존 구조에 편입)

**Performance Goals**: 제출 응답은 생성 완료 비대기(즉시), 완료 이미지는 최대 1주기(1시간) 내 회수, 회수 중 상주 메모리 이미지 1장(~4MB) 수준

**Constraints**: 결과 JSONL(배치 10건 ≈ 15~40MB) 전체 메모리 적재 금지 — 줄 단위 스트리밍. 외부 호출(OpenAI·S3)을 DB 트랜잭션 안에 두지 않는다. 람다 등 외부 워커 없음(부하 실측 전 선지불 금지)

**Scale/Scope**: 초기 대상 ~110장(≈$1.75), 배치당 10건, 이후 신메뉴 소량 추가 운영

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 작업 Red→Green→Refactor. 페이크(OpenAI 배치 포트·스토리지)로 제출 JSONL 조립·회수 파싱·수렴 전이를 실패 테스트 먼저 작성 |
| II. Bounded Contexts | PASS | `ImageBatch`·`ImageBatchItem`은 food 콘텐츠 파이프라인 메타로 `:domain:food` 소유. 타 도메인 의존 없음(food PK만 참조). 제출·회수 오케스트레이션(food+포트+스토리지 조합)은 `:application` |
| III. Layered Dependency Direction | PASS | OpenAI Batch 접근은 `:core` 포트 인터페이스(`FoodImageBatchClient`) seam — 기존 `StorageObjectStore`·`FoodImageGenerationClient` 패턴. 구현은 `:infra:llm`, 조립·스케줄은 `:app:api` |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델, `BaseEntity` 상속, public. JPA 연관관계 없음 — `ImageBatchItem.batchId`는 Long id 값, FK는 Flyway가 강제. 트랜잭션 경계는 사용하는 쪽 소유(회수는 항목별 짧은 트랜잭션) |
| V. Language Policy | N/A | 이미지 바이너리 — 언어 콘텐츠 아님 |
| 추가 제약: 외부 호출과 트랜잭션 분리 | PASS | 제출: 후보 조회(읽기) → OpenAI 업로드·생성(트랜잭션 밖) → 메타 기록(짧은 쓰기). 회수: 상태 GET·JSONL 스트리밍·S3 put 모두 트랜잭션 밖, 항목당 imageRef+item 갱신만 짧은 트랜잭션 |

**Post-Design Re-check (Phase 1 후)**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-226-food-image-batch/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── admin-food-image-batch.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/
└── food/
    └── FoodImageBatchClient.kt                  # 신규 포트: 제출·상태조회·결과 스트리밍 (기존 FoodImageGenerationClient는 삭제)

domain/food/src/main/kotlin/com/kbap/domain/food/
├── model/
│   ├── Food.kt                                  # 수렴 전이 함수 재작성(텍스트 4조건 × imageRef)
│   ├── FoodContentStatus.kt                     # TEXT_READY 추가
│   ├── ImageBatch.kt                            # 신규 엔티티
│   ├── ImageBatchItem.kt                        # 신규 엔티티
│   ├── ImageBatchStatus.kt                      # SUBMITTED/COLLECTED/FAILED
│   └── ImageBatchItemStatus.kt                  # PENDING/DONE/FAILED
├── FoodJpaRepository.kt                         # 이미지 후보 조회 쿼리 추가
├── ImageBatchJpaRepository.kt                   # 신규
└── ImageBatchItemJpaRepository.kt               # 신규

application/src/main/kotlin/com/kbap/application/foodimage/
├── FoodImageBatchSubmitService.kt               # 제출 오케스트레이션
├── FoodImageBatchCollectService.kt              # 회수 오케스트레이션 (seam: 상태조회/바이트이동/DB전이)
└── FoodImageProperties.kt                       # 모델·품질·배치크기·프롬프트(버전) 설정

infra/llm/src/main/kotlin/com/kbap/infra/llm/food/
└── OpenAiFoodImageBatchClient.kt                # 신규: RestClient 기반 Files/Batches API (기존 OpenAiFoodImageGenerationClient는 삭제)

app/api/src/main/kotlin/com/kbap/app/api/
├── admin/AdminController.kt                     # 이미지 일괄 제출 엔드포인트 추가
├── admin/AdminFoodImageSubmitResponse.kt        # 신규
└── config/
    ├── SchedulingConfig.kt                      # @EnableScheduling + ShedLock LockProvider
    └── FoodImageCollectScheduler.kt             # @Scheduled(1h) + @SchedulerLock

app/api/src/main/resources/db/migration/
├── V<ts>__image_batch_tables.sql                # image_batch · image_batch_item
├── V<ts>__shedlock.sql                          # shedlock 테이블
└── V<ts>__food_content_status_text_ready.sql    # content_status ENUM에 TEXT_READY 추가

app/batch/src/main/kotlin/com/kbap/app/batch/content/
├── FoodContentItemProcessor.kt                  # 이미지 분기·빈 스텁 제거, 수렴 전이 호출
└── FoodContentBatchConfig.kt                    # 이미지 클라이언트 주석 조립 정리
```

**Structure Decision**: 기존 모듈러 모놀리스에 편입 — 신규 모듈 없음. 메타 엔티티는 `:domain:food`(food 콘텐츠 파이프라인 소유), 오케스트레이션은 `:application`, OpenAI Batch 어댑터는 `:infra:llm`(OpenAI 키·RestClient 인프라 기존 위치), 스케줄·조립·Flyway는 `:app:api`(스키마 owner).

## Complexity Tracking

위반 없음 — 해당 없음.
