# Implementation Plan: 음식 candidate 스테이징 파이프라인 골격 + 승격 배치

**Branch**: `kb-96-food-candidate-pipeline` | **Date**: 2026-07-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-96-food-candidate-pipeline/spec.md`

## Summary

미완성 음식을 **candidate 스테이징**(`:core:research` 소유 도메인 + `:infra:persistence` JPA 어댑터)에 쌓고, 완성 조건(성분 매핑 有 && ko 설명 有 && 9언어 번역 완비 && 미승격)을 만족한 것만 **승격 배치**(`:app:batch` 독립 `ApplicationRunner`)가 `food`(+`food_avoidance_substance`)로 **멱등 업서트** 적재한다. 성분·번역을 실제로 채우는 enrichment(KB-54·KB-94)는 **픽스처로 대체**해 골격을 end-to-end 검증한다. 영속은 **JPA-first** — 대량 컬럼-스코프 hot-path 는 `@Modifying` 벌크 쿼리로 persistence context 를 우회하고, IO 규칙(컬럼-스코프 부분 업데이트·청크당 라운드트립 최소·멱등)을 강제한다(ADR-0013). Exposed 는 포트 seam 뒤 후속.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)
**Primary Dependencies**: Spring Boot 4.1 (data-jpa), Flyway(+mysql), Kotest BehaviorSpec. 신규 라이브러리 없음(JPA-first).
**Storage**: MySQL — 신규 `food_candidate` 테이블(스테이징) + 기존 `food`·`food_avoidance_substance`(서빙, 승격 대상). Flyway owner=`:app:api`.
**Testing**: Kotest `BehaviorSpec` + MySQL 8.4 Testcontainers(`:infra:persistence` testFixtures `MySqlContainerConfig`, `@ServiceConnection`). 도메인 단위(완성 판정), 영속 통합(어댑터·@Modifying), 배치 e2e(픽스처 승격).
**Target Platform**: 배치 bootJar `:app:batch`(진입점 `com.meogo.app.batch.MeogoBatchApplication`), 독립 러너 게이트 `meogo.promotion.runner.enabled`.
**Project Type**: 모듈러 모놀리스 백엔드(ADR-0008). 신규 모듈 없음.
**Performance Goals**: 대량(수천~수만 candidate) 처리 시 청크당 DB 라운드트립 상수 유지(단건 반복 금지), persistence context 적체 회피(@Modifying).
**Constraints**: 도메인 ORM-free(원칙 IV)·컨텍스트 경계 스냅샷 이관(원칙 II)·컬럼-스코프 부분 업데이트(동시성 안전)·멱등·외부 호출 없는 순수 DB 트랜잭션.
**Scale/Scope**: 미스 메뉴 누적으로 candidate 수천~수만 예상. 골격은 픽스처 규모로 검증하고 IO 규칙으로 대량 대비.

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 후 재점검.*

- **I. Test-First (NON-NEGOTIABLE)**: ✅ 전 산출물 TDD — 완성 판정(도메인 단위)·어댑터(Testcontainers)·승격 e2e(픽스처) 실패 테스트 우선.
- **II. Bounded Contexts**: ✅ candidate=`:core:research` 소유, `food`=`:core:food`. 승격은 `:app:batch` 가 두 포트를 조합하고 **스냅샷 값(음식명·설명·성분 코드+확률)으로만** 이관 — 도메인 객체 직접 공유 없음.
- **III. Layered Dependency**: ✅ `:app:batch` → `:core:research`·`:core:food`(impl) + `:infra:persistence`(runtimeOnly). `:infra:persistence` → `:core:research`(impl) **추가**(candidate 포트 구현). research 는 완전 ORM-free 유지.
- **IV. Persistence Encapsulation**: ✅ candidate JPA 엔티티·리포지토리·어댑터는 `:infra:persistence` 에 모임(`BaseEntity` 상속). 도메인은 port 만 노출, 상위는 import 안 함. ArchUnit `ModuleBoundaryTest` 가 `@Entity` 위치·의존 방향 강제.
- **V. Content Language**: ✅ candidate 는 ko 설명 + 9개 언어 번역을 보관, 승격 후 `food` 의 ko 원문 + 번역·ko 폴백 정책 유지.
- **Additional Constraints**: ✅ 승격은 외부 호출 없는 순수 DB(트랜잭션 안전). 도메인/영속을 API 로 직접 노출하지 않음. 스택 무변경(JPA-first).

**결과**: 위반 없음. Complexity Tracking 비움.

## Project Structure

### Documentation (this feature)

```text
specs/kb-96-food-candidate-pipeline/
├── plan.md              # (this file)
├── research.md          # Phase 0 — 설계 결정
├── data-model.md        # Phase 1 — candidate 스키마·엔티티·완성 판정
├── contracts/
│   └── internal-contracts.md   # 포트·승격 배치 계약(내부)
├── quickstart.md        # Phase 1 — 실행·검증 절차
└── checklists/requirements.md
```

### Source Code (repository root)

```text
core/research/src/main/kotlin/com/meogo/core/research/candidate/
├── FoodCandidate.kt              # 도메인(ORM-free): 음식명·ko설명·번역맵·성분매핑 스냅샷·승격여부, isComplete()
├── FoodCandidateRepository.kt    # 포트: findPromotable/create/markPublished/부분업데이트 시그니처
└── SubstanceSnapshot.kt          # 성분 매핑 스냅샷 값(코드+확률)

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/research/
├── FoodCandidateJpaEntity.kt     # BaseEntity 상속, toDomain()/from()
├── FoodCandidateJpaRepository.kt # Spring Data + @Modifying 벌크(컬럼-스코프 부분 업데이트)
└── FoodCandidateRepositoryAdapter.kt

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
├── FoodJpaEntity.kt              # from(domain) 컴패니언 추가(승격 적재용)
├── FoodJpaRepository.kt          # findByKoreanName(업서트 판정) 추가
└── FoodRepositoryAdapter.kt      # save(food) 구현(업서트)

app/batch/src/main/kotlin/com/meogo/app/batch/promotion/
├── FoodPromotionJob.kt           # candidate 조회→Food 조립→save→markPublished (음식 1건=TX 1개)
├── FoodPromotionJobRunner.kt     # ApplicationRunner, meogo.promotion.runner.enabled
└── PromotionJobConfig.kt         # 빈 조립

app/api/src/main/resources/db/migration/
└── V2026.07.07.*.__create_food_candidate.sql   # Flyway owner=api

core/food/src/main/kotlin/com/meogo/core/food/
└── FoodRepository.kt             # save(food): Food 추가
```

**Structure Decision**: 기존 모듈 구조를 그대로 재사용한다(ADR-0008). 신규 모듈·신규 라이브러리 없음. candidate 도메인은 `:core:research` 하위 `candidate` 패키지, 영속은 `:infra:persistence` 하위 `research` 패키지(엔티티는 컨텍스트별 패키지 규약). `:infra:persistence` build 에 `implementation(project(":core:research"))` 한 줄 추가.

## Complexity Tracking

> 위반 없음 — 표 비움. (JPA-first 채택으로 신규 영속 스택 도입에 따른 복잡도를 회피했다. Exposed/대량 IDENTITY-INSERT 최적화는 규모 임계에서 후속 ADR — ADR-0013 "후속" 참조.)
