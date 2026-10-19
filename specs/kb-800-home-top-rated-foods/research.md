# Research — KB-800

- **Decision**: native 집계 쿼리 1개(`food_review` join `food`, group by food_id). **Rationale**: avg(nullif(x,0)) 와 총평점 정렬식을 DB 에서 처리해 상위 20개만 가져온다. **Alternatives**: JPQL — nullif/boolean 합산 정렬이 번거롭다. 전체 로드 후 앱 정렬 — 불필요한 전송.
- **Decision**: native 이므로 `r.status = 'ACTIVE'` 직접 기술. **Rationale**: `@SQLRestriction` 은 native 쿼리에 적용되지 않는다.
- **Decision**: 캐시·인덱스·마이그레이션 없음. **Rationale**: 기존 `idx_food_review_food_recent(food_id, id)` 위 group by. 느려지면 `PopularFoodIdCache` 패턴으로 이동(코드 `ponytail:` 주석).
- **Decision**: 1.3 미만은 `topRatedFoods = null` + `@JsonInclude(NON_NULL)`. **Rationale**: 선례 `ReviewResponse.food`, 키 자체 생략.
