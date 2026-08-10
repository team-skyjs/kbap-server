# Implementation Plan: 음식 콘텐츠 파이프라인 랭체인 전환 — 아웃박스 적재·결과 수신·재수집

**Branch**: `kb-302-langchain-food-ingest` | **Date**: 2026-08-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-302-langchain-food-ingest/spec.md`

## Summary

외부 콘텐츠 생성 파이프라인(kbap-langchain 람다)에 보낼 음식 콘텐츠 수집 요청을 **아웃박스 테이블**(`food_content_outbox`)에 쌓고, 파이프라인이 돌려준 결과를 적재한다. 결과는 `POST /api/v1/admin/foods/contents` 로 도착하며 **`foodId` 로만 대상을 특정**한다.

**큐 발행은 이번 범위 밖이다** — 아웃박스 행을 SQS 로 내보내는 주체는 후속 티켓의 배치 잡이 소유한다. 이번 작업이 끝나면 `PENDING` 행이 쌓이고, 발행은 아직 일어나지 않는다. 그래서 발행 seam(`common.port.mq`)·SQS 어댑터 모듈(`:infra:mq`)·스케줄러를 **만들지 않는다**(소비자 없는 인터페이스를 미리 두지 않는다). 메시지 계약만 [contracts/mq-message.md](./contracts/mq-message.md) 에 합의된 상태로 남겨 후속 티켓이 그대로 쓴다.

핵심 설계 두 가지:

1. **사진 재활용** — 성공 결과 적용 시 상태 결정을 `imageRef` 유무로 가른다(있으면 `PENDING_REVIEW`, 없으면 `PENDING_IMAGE`). 이미지 생성 후보 조회(`findImageCandidates`)는 `PENDING_IMAGE` 만 보므로, 사진이 있는 음식은 구조적으로 재생성 대상이 되지 않는다. 이미지 파이프라인 코드는 손대지 않는다.
2. **서비스 무중단 재수집** — 대상이 이미 `READY` 면 텍스트만 덮고 상태를 바꾸지 않는다. 실패 결과도 `READY` 는 내리지 않고 실패 기록만 남긴다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(web·data-jpa·validation), Flyway, Thymeleaf(관리자 화면). **신규 의존 없음** — AWS SQS SDK 는 발행을 맡을 후속 티켓에서 들어온다.

**Storage**: MySQL(단일) — 신규 테이블 `food_content_outbox`, `food` 컬럼 1개 추가

**Testing**: Kotest `BehaviorSpec` + JUnit5 플랫폼, 통합 테스트는 MySQL Testcontainers(`:common` testFixtures)

**Target Platform**: Linux 서버(운영 api 2대)

**Project Type**: 모듈러 모놀리스 백엔드(web bootJar `:api`)

**Performance Goals**: 일괄 재수집 요청은 대상 건수만큼의 행 삽입으로 끝나고 외부 호출을 기다리지 않는다.

**Constraints**: 일괄 재수집 1회 상한 500건. 적재 API 는 람다가 직접 호출하므로 계약 위반 요청을 저장 전에 거절해야 한다.

**Scale/Scope**: 음식 데이터 수천 건 규모. 신규 파일 ~8개, **신규 Gradle 모듈 없음**, Flyway 마이그레이션 2개.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| **I. Test-First** | PASS | 모든 task 는 실패 테스트 선행. 엔티티 상태 규칙(단위) → 아웃박스 적재(통합) → 적재 API(MockMvc) → 관리자 일괄 재수집(통합) 순. |
| **II. Bounded Contexts** | PASS | 아웃박스는 food 컨텍스트 소유 → `common.domain.food`. 다른 컨텍스트를 참조하지 않고 `foodId`(Long) 로만 든다. |
| **III. Layered Dependency** | PASS | 이번 범위엔 외부 시스템 호출이 없다(적재는 우리가 받는 쪽). 따라서 seam·infra 어댑터를 만들지 않는다 — 발행이 들어오는 후속 티켓이 `common.port.mq` seam + `:infra:mq` 구현 + 조립의 3분할을 세운다. |
| **IV. Persistence Ownership** | PASS | 엔티티 `common.domain.food.model.FoodContentOutbox`(BaseEntity 상속, JPA 연관 없음 — `food_id` Long). 리포지토리 public, 트랜잭션 경계는 호출 서비스가 명시 선언. 상태 전이 로직은 엔티티(`Food.applyContent`·`recordContentFailure`) 소유. |
| **V. Language Policy** | PASS | 9개 언어 전수·빈 값 불가 검증은 **요청 경계(적재 요청 DTO)** 가 소유하고, 도메인은 확정값을 받는다. |

**위반 없음** — Complexity Tracking 비움.

**Phase 1 설계 후 재검토(PASS)**: 아웃박스 엔티티가 `food_id` 를 값으로 들어 JPA 연관 금지(원칙 IV)를 지키고, 검증은 요청 DTO 가 소유해 원칙 V 를 지킨다. 발행이 빠지면서 모듈·seam 추가가 0 이 됐다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-302-langchain-food-ingest/
├── plan.md              # 이 파일
├── spec.md
├── research.md          # Phase 0 — 결정과 기각 대안
├── data-model.md        # Phase 1 — 엔티티·스키마·상태 규칙
├── quickstart.md        # Phase 1 — 로컬 확인 절차
├── contracts/
│   ├── ingest-api.md    # 적재 API 계약(위키 계약의 이번 개정판)
│   └── mq-message.md    # SQS 메시지 계약 — 합의만, 구현은 후속 티켓
└── checklists/requirements.md
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/food/
├── model/Food.kt                        # 변경: contentFailureKind 필드 + applyContent·recordContentFailure
├── model/FoodContentFailureKind.kt      # 신규: NOT_FOOD·JUDGE_REJECTED·INGREDIENT_GUARD
├── model/FoodContentOutbox.kt           # 신규: 아웃박스 엔티티
├── model/FoodContentOutboxStatus.kt     # 신규: PENDING·SENT
├── FoodContentOutboxJpaRepository.kt    # 신규
└── FoodService.kt                       # 변경: createIncomplete 가 아웃박스 행도 적재

api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodContentIngestController.kt  # 신규: POST /api/v1/admin/foods/contents
├── AdminFoodContentIngestApi.kt         # 신규: swagger 문서 전용 인터페이스
├── AdminFoodContentIngestRequest.kt     # 신규: 요청 DTO + 검증
├── AdminFoodContentIngestService.kt     # 신규: 조회 → 엔티티 위임
├── AdminFoodService.kt                  # 변경: requestRecollect(조건 일괄) + 상세뷰에 실패 유형
└── AdminFoodPageController.kt           # 변경: 재수집 폼 POST

api/src/main/resources/
├── db/migration/V2026.08.11.*__food_content_failure_kind.sql       # 신규
├── db/migration/V2026.08.11.*__food_content_outbox_table.sql      # 신규
└── templates/admin/food-list.html          # 변경: 재수집 버튼·확인·실패 유형 표시
```

**Structure Decision**: 기존 모듈 구조를 그대로 따르고 **모듈을 추가하지 않는다**. 영속(아웃박스 엔티티·리포지토리·도메인 규칙)은 `:common`, HTTP 경계와 관리자 조작은 `:api`. 후속 발행 티켓이 `common.port.mq` seam·`:infra:mq` 어댑터·발행 배치 잡을 얹는다 — 이번 산출물 중 그때 바뀌는 것은 없다(아웃박스 행을 읽어 가기만 한다).

## Complexity Tracking

위반 없음 — 해당 없음.
