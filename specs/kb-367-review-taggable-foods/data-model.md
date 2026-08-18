# Data Model: 리뷰 태그 가능 음식 목록 조회 API

**스키마 변경 없음** — 읽기 전용 기능. 엔티티·마이그레이션·인덱스 추가 없다.

## 원천 데이터 (기존)

- `scan_history` — 스캔 추출 항목 1건 = 1 row. `member_id`·`food_id`(nullable, 미매칭이면 null)·`created_at`·`status`. 인덱스 `idx_scan_history_recent(member_id, created_at)`.
- `food` — `status='ACTIVE'`·`content_status='READY'` 만 노출 대상(기존 규칙).

## 파생 뷰 (신규 쿼리, `ScanHistoryJpaRepository`)

태그 가능 음식 페이지 ids:

```sql
select t.food_id from (
    select sh.food_id as food_id, max(sh.created_at) as last_scanned_at
    from scan_history sh
    join food f on f.id = sh.food_id
    where sh.member_id = :memberId
      and sh.status = 'ACTIVE'
      and f.status = 'ACTIVE'
      and f.content_status = 'READY'
      and (:kw is null or f.display_name collate utf8mb4_unicode_ci like concat('%', :kw, '%') escape '\\'
           or (:jsonPath is not null and json_unquote(json_extract(f.name_translations, :jsonPath))
               collate utf8mb4_unicode_ci like concat('%', :kw, '%') escape '\\'))
    group by sh.food_id
) t
where (:cursorLastScannedAt is null
       or t.last_scanned_at < :cursorLastScannedAt
       or (t.last_scanned_at = :cursorLastScannedAt and t.food_id < :cursorFoodId))
order by t.last_scanned_at desc, t.food_id desc
limit :size
```

- 중복 제거 = `group by sh.food_id`, 정렬 = `(max(created_at), food_id)` 내림차순, `food_id is null`(미매칭)은 join 으로 자연 제외.
- keyword 매칭 조건은 기존 `searchFoodPageIds` 와 동일한 형태(escape·collate·jsonPath 포함).
- 보조 쿼리(커서 재계산): `select max(sh.created_at) from scan_history sh where sh.member_id=:memberId and sh.food_id=:foodId and sh.status='ACTIVE'` — null 이면 비정상 커서(400).

## 응답 계약 재사용

- `Page<FoodSummaryResponse>` — 기존 음식 목록·검색과 동일 봉투·항목(`FoodSummaryView` 매핑, 북마크·평점 요약 일괄 조회 재사용). `nextCursor` = 마지막 항목 foodId(Long).
