# Internal Contracts: candidate 스테이징 파이프라인 (KB-96)

외부 API 는 없다(배치 파이프라인). 아래는 **모듈 간 포트 계약**과 **승격 배치 동작 계약**이다. 시그니처는 방향을 고정하기 위한 것이며 구현 세부(어댑터)는 plan/data-model 참조.

## 포트 1 — `FoodCandidateRepository` (`:core:research`)

candidate 스테이징의 도메인 포트. 어댑터는 `:infra:persistence`(JPA + `@Modifying` 벌크).

```kotlin
interface FoodCandidateRepository {
    // 생성 — 메뉴명 + ko 설명 확보(번역·성분은 빈 값으로 시작). 이미 있으면 무시/반환.
    fun create(koreanName: String, koreanDescription: String): FoodCandidate

    // 승격 대상 조회 — DB 술어로 완성·미승격 후보만 페이지 조회.
    fun findPromotable(page: Int, size: Int): List<FoodCandidate>

    // 컬럼-스코프 부분 업데이트 (KB-54/KB-94 가 각자 자기 컬럼만) — @Modifying 벌크, 타 컬럼 보존.
    fun updateSubstanceMapping(candidateId: Long, mapping: List<SubstanceSnapshot>)   // KB-54
    fun updateDescriptionTranslations(candidateId: Long, translations: Map<LanguageCode, String>)  // KB-94

    // 승격 마킹 — published_food_id 링크(멱등). 균일 아님(행별 food id) → 건별/배치.
    fun markPublished(candidateId: Long, foodId: Long)
}
```

**계약**:
- `create` 는 `korean_name` UNIQUE 라 중복 생성하지 않는다(존재 시 기존 반환).
- `findPromotable` 은 `published_food_id IS NULL AND description IS NOT NULL AND JSON_LENGTH(description_translations)=9 AND JSON_LENGTH(substance_mapping)>0` 를 만족하는 것만, `id` 오름차순 페이지로 반환.
- `updateSubstanceMapping`/`updateDescriptionTranslations` 는 **해당 컬럼만** 갱신하고 다른 컬럼·행을 건드리지 않는다(동시 실행 안전, SC-004). 엔티티 통째 merge 금지.
- 모든 메서드는 멱등 재호출에 안전(같은 값 재적용 무해).

## 포트 2 — `FoodRepository.save` (`:core:food`, 추가)

```kotlin
interface FoodRepository {
    fun findByKoreanName(name: String): Food?     // (기존)
    fun save(food: Food): Food                     // (추가) korean_name 업서트, 저장된(id 포함) Food 반환
}
```

**계약**:
- `save` 는 `food.korean_name` 이 이미 있으면 **update**, 없으면 **insert**(업서트) — 중복 row 를 만들지 않는다(SC-003).
- `food` 저장 시 연관 `food_avoidance_substance` 를 전달된 성분으로 재적재한다(정규화).
- 반환 `Food` 는 영속 id 를 갖는다(승격 마킹의 `foodId` 로 사용).

## 동작 계약 — 승격 배치 `FoodPromotionJob` (`:app:batch`)

```
run():
  page = 0
  loop:
    batch = candidateRepo.findPromotable(page, SIZE)
    if batch empty: break
    for c in batch:                      # 음식 1건 = 트랜잭션 1개
      try (tx):
        food = foods.save(c.toFood())    # 스냅샷 값으로 Food 조립 → 업서트
        candidateRepo.markPublished(c.id, food.id)
      catch: log, continue               # 실패 격리 — 나머지 진행, 실패분은 다음 실행
    page++
```

**불변식**:
- 완성(`isComplete`) 아닌 candidate 는 절대 `food` 로 적재되지 않는다(FR-004/SC-002).
- 이미 `published_food_id` 있는 candidate 는 `findPromotable` 에서 제외 → 재실행해도 중복 `food` 없음(SC-003).
- 한 건 실패가 다른 건 적재를 막지 않는다(FR-006/SC-005).
- 승격 트랜잭션에 **외부(LLM) 호출 없음** — 순수 DB(헌법 Additional Constraints).
- 러너는 `meogo.promotion.runner.enabled=true` 일 때만 실행(기본 off, 기존 스코어링 러너 패턴).

## 관측 계약

- 승격 배치 완료 시 `total / promoted / failed` 집계 로그(FR-010 유형, 스코어링 러너 로그와 동일 스타일).
