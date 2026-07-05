# Phase 1 Data Model: 삭제된 기피 성분 skip 처리

**스키마 변경 없음.** 본 기능은 기존 데이터 구조 위에서 조회 시점 조합 불일치를 흡수한다. 아래는 관련 엔티티와 불일치가 발생하는 지점의 기술이다.

## 관련 엔티티 (기존)

### Food (`:core:food`)
- `id: Long?`, `content(name/description)`, `imageRef: String?`, `spiciness: FoodSpiciness`, `avoidanceSubstances: List<FoodAvoidanceSubstance>`
- 상세조회 대상 애그리거트. 성분을 **코드로 참조**한다(원칙 II).

### FoodAvoidanceSubstance (`:core:food`)
- `substanceCode: AvoidanceSubstanceCodeRef`(코드 값 래퍼), `inclusionProbability: Int`
- 음식→성분 참조. **성분이 소프트 삭제돼도 이 참조는 남는다** — 불일치의 원천.

### AvoidanceSubstance (`:core:avoidance`)
- `id`, `code: AvoidanceSubstanceCode`, `name: LocalizedText`(ko + 번역)
- 성분 카탈로그 항목. 영속(`:infra:persistence`)에서 `BaseEntity` 상속 → `status` ACTIVE/DELETED 소프트 삭제 + `@SQLRestriction("status='ACTIVE'")`.

## 불일치 지점 (핵심)

```
FoodAvoidanceSubstance.substanceCode  ──참조──▶  AvoidanceSubstance(code)
        (항상 존재)                                  (status=DELETED 면 ACTIVE 조회에서 제외)
```

- FK `fk_fas_substance` 는 `code` 값만 보고 `status` 를 보지 않아 성분의 소프트 삭제를 막지 못한다.
- `AvoidanceSubstanceRepository.findByCodes(codes)` 는 `@SQLRestriction` 때문에 **ACTIVE 성분만 반환** → 삭제된 code 는 결과 카탈로그 맵에서 누락 → `catalog[code] == null`.

## 조회 시 상태 규칙 (신규 — 코드 레벨, 무스키마)

| 참조 성분의 카탈로그 상태 | 처리 |
|--------------------------|------|
| ACTIVE (카탈로그에 존재) | 기존대로 표시명·확률·위험도 조립 |
| DELETED/부재 (`catalog[code]==null`) | **skip** — 결과 목록에서 제외 + WARN 로그(`foodId`, `substanceCode`) |

- 정렬: 생존 성분은 기존 확률 내림차순 순서를 유지한다.
- 전부 부재: 성분 목록은 빈 목록. 음식명·설명·맵기 등 나머지는 정상.

## 변경 없는 것

- DB 스키마·Flyway 마이그레이션: **없음**.
- JPA 엔티티·리포지토리·`@SQLRestriction`: **불변**.
- 도메인 모델(Food·FoodAvoidanceSubstance·AvoidanceSubstance): **불변**.
- 응답 DTO(`FoodDetailResult`·`FoodDetailResponse`): **불변**.
