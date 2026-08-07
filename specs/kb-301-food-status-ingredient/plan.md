# Implementation Plan: food 상태 enum 간소화 및 기피성분 컬럼명 ingredient 변경

**Branch**: `kb-301-food-status-ingredient` | **Date**: 2026-08-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-301-food-status-ingredient/spec.md`

## Summary

food 상태를 6값(INCOMPLETE·PENDING_IMAGE·PENDING_REVIEW·REVIEWED·REVIEW_REJECTED·READY)에서 4값(**FAILED·PENDING_IMAGE·PENDING_REVIEW·READY**)으로 간소화하고, `avoidance_substances` JSON 컬럼을 **`ingredient`** 로, 성분 카탈로그 테이블 `avoidance_substance` 를 **`ingredients`** 로 개명한다(R7 — 코드 어휘는 avoidance 유지). 접근: Flyway 3단계 ENUM 변경(확장→매핑 UPDATE→축소)+컬럼 RENAME 단일 마이그레이션, 엔티티 상태 전이 메서드를 승인 플로우(approve/reject/resubmit/attachImage)로 재배선, INCOMPLETE 전제인 배치 콘텐츠 잡·배치 전용 공용 코드는 **삭제하지 않고 전량 주석 처리로 보존**(사용자 결정 — research R2, 최종 삭제는 KB-302), 개명은 도메인→응답 전 계층 일관 적용. 상세 결정은 [research.md](research.md), 모델·매핑은 [data-model.md](data-model.md), 계약 변경은 [contracts/api-changes.md](contracts/api-changes.md).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Flyway(+flyway-mysql), springdoc

**Storage**: MySQL (`food` 테이블 — content_status ENUM·ingredient JSON), 마이그레이션 Flyway(owner=`:api`)

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit 플랫폼, 통합은 MySQL Testcontainers(`@ServiceConnection`) + Flyway on + `ddl-auto=validate`

**Target Platform**: Linux 서버 (api bootJar; batch bootJar 는 콘텐츠 잡 삭제 후 껍데기 유지)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 (`:common`·`:api`·`:batch`·`:infra:*`)

**Performance Goals**: 해당 없음 — 스키마·도메인 리팩터. 마이그레이션은 food 행 수 규모(수천 건)에서 즉시 완료

**Constraints**: 마이그레이션은 다른 미적용 마이그레이션과 순서 독립·프로덕션 적용 후 수정 불가, 기존 데이터 유실 0(SC-002), 사용자 노출 집합 불변(SC-003), KB-302 전에도 전 모듈 빌드 그린

**Scale/Scope**: main 소스 접점 — 상태: common 3파일·api 5파일·batch 5파일(주석 처리 보존), 개명: common 5파일·api 5파일 + Flyway 1건 + 테스트 ~25파일(시드·손스텁 포함)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 상태 전이·매핑·개명 각각 실패 테스트 선행(Red→Green). 마이그레이션 매핑은 Testcontainers 로 구 상태 시드→신 상태 분포 검증 |
| II. Bounded Contexts | PASS | food 컨텍스트 내부 변경. 도메인 간 의존 방향 변화 없음(`ModuleBoundaryTest` 맵 무수정). avoidance 어휘는 회원 기피 컨텍스트에 그대로 |
| III. Layered Dependency | PASS | 모듈 그래프 불변. batch 콘텐츠 잡 삭제는 batch→common 의존을 줄이는 방향 |
| IV. Persistence Ownership | PASS | 상태 전이는 엔티티(=도메인 모델) 메서드 소유, 스키마는 api Flyway 소유, JPA 연관 신설 없음 |
| V. Language Policy | PASS | 번역·언어 정책 접점 없음. ingredient 데이터(성분 코드·확률) 값 불변 |

**Post-design 재평가**: PASS — Phase 1 산출물이 신규 모듈·계층·연관을 도입하지 않음. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-301-food-status-ingredient/
├── plan.md              # 이 파일
├── research.md          # R1~R6 결정
├── data-model.md        # 스키마 변경·상태 머신·매핑표·삭제 대상
├── quickstart.md        # 검증 명령·함정
├── contracts/api-changes.md
└── tasks.md             # /speckit-tasks 산출 (미생성)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/food/
├── model/FoodContentStatus.kt     # 4값으로 재정의
├── model/Food.kt                  # 전이 메서드 재배선(approve/reject/resubmit/attachImage), needs*/transitionByContentState 삭제, ingredients 개명
├── model/FoodAvoidanceItem.kt     # → FoodIngredient.kt 개명
├── FoodJpaRepository.kt           # INCOMPLETE 벌크 전이·카운트 쿼리 삭제
├── FoodService.kt                 # 센티널 생성(Food.failed)·개명 반영
└── dto/GetFoodDetailResult.kt     # 개명 반영

api/src/main/kotlin/com/kbap/api/
├── admin/{AdminFoodService,AdminFoodDashboardService,AdminFoodContentReviewService,AdminFoodContentReviewResponse,AdminFoodPageController,AdminFoodContentReviewApi}.kt  # 4값·승인 플로우·개명
└── food/FoodDetailResponse.kt     # 내부 참조 개명(외부 필드 불변)

api/src/main/resources/db/migration/
└── V<timestamp>__food_status_simplify_and_ingredient_rename.sql

batch/src/main/kotlin/com/kbap/batch/content/   # 5파일 전량 주석 처리 (보존 — KB-302 에서 정리)

각 모듈 src/test/                  # 미러 구조 — 상태·개명 테스트 재작성, batch/content 테스트 주석 처리,
                                   # 스캔 손스텁 CREATE TABLE·시드 INSERT 갱신 (quickstart 함정 참조)
```

**Structure Decision**: 기존 모듈·패키지 구조 그대로. 신규 파일은 Flyway 마이그레이션 1건뿐, 나머지는 제자리 수정·개명·주석 처리(보존).
