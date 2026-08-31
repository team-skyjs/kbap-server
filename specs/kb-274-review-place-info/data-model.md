# Data Model: 리뷰 작성 시 식당(장소) 검색·선택 저장 (kb-274)

> 검색(US1)은 **무영속** — 검색 결과는 저장하지 않는 일시 데이터라 엔티티·스키마 영향이 없다. seam 값 타입(`FoundPlace`)은 contracts/place-search-api.md 참조. 아래는 저장(US2~US4) 쪽만 다룬다.

## 엔티티 변경

### Review (`common.domain.review.model.Review` — 기존 수정)

| 필드 | 타입 | 변경 | 비고 |
|------|------|------|------|
| place | `ReviewPlace?` | 추가 | `@Embedded` — 전 내부 필드 null 이면 Hibernate 가 null 로 로드 |

- `update(rating, content, imageRefs, place)` — 시그니처에 place 추가, 전량 교체(생략=제거).
- `requireValid` 에 place 길이 검증 위임(`ReviewPlace.init` 이 자체 검증하므로 엔티티는 전달만).

### ReviewPlace (`common.domain.review.model.ReviewPlace` — 신규 `@Embeddable` 값 객체)

| 필드 | 컬럼 | 타입 | 제약 |
|------|------|------|------|
| name | `place_name` | `String?` | `@Column(length = 100)` — 초과 시 `require` 실패 |
| address | `place_address` | `String?` | `@Column(length = 200)` |
| latitude | `place_latitude` | `BigDecimal?` | `decimal(10,7)`, -90 ~ 90 |
| longitude | `place_longitude` | `BigDecimal?` | `decimal(10,7)`, -180 ~ 180 |

- 전 필드 nullable — 항목 단위 결측 허용(R4). JPA 연관관계 아님(헌법 IV 준수 — 값 객체).
- `init` 블록에서 길이·좌표 범위 `require` 검증 + 길이 상수(`MAX_NAME_LENGTH = 100` 등) 소유.

## 스키마 변경 (Flyway — `:api` owner)

`V<생성시각 timestamp>__food_review_place_columns.sql`:

```sql
ALTER TABLE `food_review`
    ADD COLUMN `place_name`      varchar(100)  NULL,
    ADD COLUMN `place_address`   varchar(200)  NULL,
    ADD COLUMN `place_latitude`  decimal(10,7) NULL,
    ADD COLUMN `place_longitude` decimal(10,7) NULL;
```

- 전부 NULL 허용 → 기존 row 무변경·기존 INSERT(테스트 시드 포함) 무영향, 마이그레이션 독립 실행 가능(순서 비의존).
- 인덱스 없음(R3 — 식당별 조회는 스코프 밖).

## 상태 전이

없음 — place 는 리뷰의 부가 속성이며 자체 상태를 갖지 않는다. 소프트 삭제는 기존 `BaseEntity.status` 그대로.

## 검증 규칙 요약

| 계층 | 규칙 |
|------|------|
| web DTO (`ReviewPlaceRequest`) | `@Size(max=100/200/30)` 문자열 3종, `@DecimalMin/Max` 위도(-90~90)·경도(-180~180). place 자체는 선택(`null` 허용), `@Valid` 중첩 검증 |
| 도메인 (`ReviewPlace.init`) | 동일 제약 `require` 이중 방어 — 위반 시 예외 |
| DB | varchar 길이·decimal 정밀도 최종 강제 |
