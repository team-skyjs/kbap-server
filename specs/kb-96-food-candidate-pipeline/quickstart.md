# Quickstart: candidate 스테이징 파이프라인 (KB-96)

**Plan**: [plan.md](./plan.md) | **Contracts**: [contracts/internal-contracts.md](./contracts/internal-contracts.md)

## TDD 구현 순서 (Red→Green→Refactor)

1. **도메인 `FoodCandidate.isComplete()`** — 순수 단위(BehaviorSpec). 성분 0·ko null·번역 8개/10개·이미 승격 등 경계에서 완성/미완성 판정. (Red 먼저)
2. **`food_candidate` Flyway 마이그레이션** + `FoodCandidateJpaEntity`(BaseEntity·JSON 컬럼·toDomain/from).
3. **`FoodCandidateRepository` 어댑터**(Testcontainers 통합) — `create`(중복 무시), `findPromotable`(술어), `updateSubstanceMapping`/`updateDescriptionTranslations`(**컬럼-스코프**: 한 컬럼 갱신 후 다른 컬럼 보존 검증), `markPublished`.
4. **`FoodRepository.save` 업서트**(Testcontainers) — 신규 insert / 동일 korean_name update, `food_avoidance_substance` 재적재.
5. **승격 배치 `FoodPromotionJob`**(e2e, 픽스처) — 아래 시나리오.
6. **러너·구성** `FoodPromotionJobRunner`(`meogo.promotion.runner.enabled`)·`PromotionJobConfig`.

## e2e 검증 시나리오 (픽스처 승격)

Testcontainers MySQL 부트스트랩 위에서:

```
given: candidate 3건
  A: 성분+ko+번역9  (완성)
  B: 성분+ko, 번역 7개 (미완성)
  C: 성분+ko+번역9, 이미 published_food_id 세팅 (승격됨)
when: FoodPromotionJob.run()
then:
  - A → food 에 적재(+food_avoidance_substance), candidate A.published_food_id 세팅
  - B → 적재 안 됨, 스테이징 잔류
  - C → 중복 적재 안 됨(재실행에도 food row 1개)
  - 재실행 시 A 도 추가 적재 없음(멱등)
```

동시성(SC-004): 한 candidate 에 `updateSubstanceMapping` 후 `updateDescriptionTranslations` 를 적용해 두 컬럼이 모두 보존되는지(서로 안 덮음) 검증.

## 로컬 실행

```bash
# 승격 배치만 (로컬 프로필, DB 연결 필요)
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun \
  --args='--meogo.promotion.runner.enabled=true'
```

- 기본 off — 인자로만 켠다(스코어링 러너와 동일 패턴). datasource 는 활성 프로필에서 공급.
- MySQL JDBC URL 에 `rewriteBatchedStatements=true`(대량 배치 IO — ADR-0013). 프로필 datasource URL 에 반영.

## 마이그레이션 검증 (기존 관행)

Flyway 는 테스트에서 비활성(Testcontainers·H2 아님)이므로, `food_candidate` 마이그레이션 SQL 은 로컬 docker MySQL 에 DROP+CREATE 후 부팅으로 별도 검증한다(기존 flyway-validation-gap 관행). 엔티티 컬럼 길이·타입을 마이그레이션과 일치시킨다.

## 완료 게이트

- 헌법 I: 위 1·3·4·5 가 실패 테스트 우선으로 작성·통과.
- ArchUnit `ModuleBoundaryTest`: candidate `@Entity` 가 `:infra:persistence` 에만, `:core:research` 는 ORM-free, 의존 방향 준수.
- 픽스처 e2e(SC-007)로 생성→승격 완결. KB-54·KB-94 는 이 위에 실제 enrichment 를 얹는다.
