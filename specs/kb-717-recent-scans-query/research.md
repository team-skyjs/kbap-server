# Research: 홈 최근 스캔 조회 쿼리 DB 레벨 최적화

2026-10-05, 시드된 dev(MySQL 8.4.9) 에서 읽기 전용으로 확인한 사실과 그에 따른 결정.

## 확인한 사실

| 항목 | 값 |
|---|---|
| scan_history 전체 | 171,667행 |
| 측정 회원(id 3) | 4,477행 · 음식 853종 |
| food 전체 / READY·ACTIVE | 5,213 / 4,590 |
| 삭제된 스캔 행 | 0 |
| scan_history 인덱스 | PRIMARY, `fk_scan_history_food (food_id)`, `idx_scan_history_member_food_recent (member_id, food_id, created_at)` |
| 인덱스 통계의 PK cardinality | 9,851 (실제 171,667 — 시드 후 통계가 갱신되지 않았다) |

### 현재 쿼리의 실행 계획 (EXPLAIN)

```
f   ref  idx_food_content_status  rows=4590  Using index condition; Using where; Using temporary; Using filesort
sh  ref  idx_scan_history_member_food_recent (const, f.id)  rows=1  Using where
```

- **음식이 드라이빙 테이블이다.** READY 음식 4,590개를 먼저 훑고 음식마다 스캔 인덱스를 찍는다. Jira 후보 (3)이 실제로 일어나고 있다. 통계가 낡은 것(행 수를 1/17 로 봄)이 이 선택에 영향을 줬을 수 있다.
- **`sh` 가 Using where 이고 Using index 가 아니다.** 소프트 삭제 조건 `status = 'ACTIVE'` 가 인덱스에 없어 스캔 행마다 본 테이블을 읽는다. JPQL 은 `@SQLRestriction` 이 이 조건을 자동으로 붙이므로 쿼리에서 뺄 수 없다.
- **임시 테이블에 음식 행 전체가 들어간다.** `select f.* ... group by f.id` 라 임시 테이블 한 행이 JSON 컬럼 3개와 긴 설명을 포함한다. 그룹 853개 × 넓은 행이다.

### 형태만 바꾼 쿼리의 실행 계획

스캔 이력만 먼저 묶고 그 결과를 음식과 결합하는 형태:

```
<derived2>  ALL     rows=917   Using where; Using filesort
f           eq_ref  PRIMARY (t.food_id)
sh (DERIVED) ref idx_scan_history_member_food_recent (const) rows=9172  Using where
```

- 음식은 PK 로만 붙는다. 드라이빙 테이블 역전이 구조적으로 불가능해진다(힌트 불필요).
- 임시 테이블과 정렬 대상이 `(food_id, max(created_at))` 두 컬럼으로 좁아진다.
- `status` 조건을 빼고 같은 집계를 돌리면 `Using index` 가 된다. 즉 `status` 를 인덱스에 넣으면 본 테이블을 읽지 않는다.

### 측정 도구의 한계

MCP `mysql-dev` 는 `EXPLAIN ANALYZE` 를 파싱하지 못하고(일반 `EXPLAIN` 만 가능), 실행 시간에는 터널 왕복 약 11 ms 가 섞인다. 정밀 수치는 터널에 mysql 클라이언트를 직접 붙여 `EXPLAIN ANALYZE` 로, 최종 판정은 RDS 슬로우 로그로 한다.

## 결정

### D1. 쿼리 형태: 스캔 이력 선집계 후 음식 PK 결합 (Jira 후보 1·3·4 를 한 번에)

- **Decision**: 회원의 스캔 이력을 `food_id` 로 묶어 `(food_id, max(created_at))` 를 만든 파생 테이블을 음식과 PK 로 결합하고, READY 필터·정렬·limit 을 그 뒤에 적용한다. JPQL 파생 서브쿼리로 써서 리포지토리 시그니처(`findRecentScannedFoods(memberId, pageable): List<RecentScannedFood>`)와 SQL 문 수(1개)를 유지한다. Hibernate 가 파생 서브쿼리 SQL 을 의도대로 내지 못하면 같은 형태의 네이티브 쿼리로 음식 id·시각만 받고 음식은 기존 `findByIdIn` 으로 싣는다(SQL 1개 증가 — 이 경우 PR 에 명시).
- **Rationale**: 드라이빙 테이블 역전이 사라지고, 임시 테이블이 두 컬럼으로 줄며, 음식 전체 행 적재는 최종 10개에만 일어난다. 테스트·서비스 코드는 그대로다.
- **Alternatives considered**:
  - 상위 N개만 뽑아 음식과 결합(후보 1의 "넉넉히 N"): READY 탈락이 N 을 넘으면 결과가 모자란다(FR-002 위반 가능). 853개 PK 조회는 싸므로 전부 결합하고 limit 을 뒤에 둔다.
  - 조인 순서 힌트(`STRAIGHT_JOIN`): JPQL 로 못 쓰고, 형태 변경이 같은 효과를 구조적으로 준다.
  - 음식 컬럼을 좁혀 조회(후보 4): 반환 타입이 엔티티가 아니게 돼 서비스·뷰 조립을 고쳐야 한다. 선집계로 넓은 행이 10개로 줄어 실익이 작다.

### D2. 인덱스: `(member_id, status, food_id, created_at)` 로 교체

- **Decision**: `idx_scan_history_member_food_recent` 를 지우고 같은 ALTER 에서 `idx_scan_history_member_status_food_recent (member_id, status, food_id, created_at)` 를 만든다.
- **Rationale**: 선집계가 인덱스만으로 끝난다(본 테이블 4,477회 조회 제거). `member_id`·`status` 가 상수이므로 `food_id` 그룹과 `max(created_at)` 을 인덱스 순서로 얻어 임시 테이블 없이 묶을 수 있다(루스 인덱스 스캔 또는 인덱스 순서 그룹핑 — 어느 쪽인지는 EXPLAIN ANALYZE 로 확인). 같은 인덱스를 쓰는 나머지 조회(목록 페이지·검색·`findLastScannedAt`·`existsByMemberIdAndFoodId`)도 전부 `member_id = ? and status = 'ACTIVE'` 를 가지므로 그대로 또는 더 잘 탄다(FR-006). `member_id` 가 맨 앞이라 회원 FK 가 요구하는 인덱스 조건도 유지된다.
- **블루/그린 공존**: 구 코드의 쿼리도 `member_id`·`status` 등치 조건을 가지므로 새 인덱스 위에서 지금과 같거나 낫다.
- **Alternatives considered**:
  - `(member_id, created_at desc, food_id)` + 최신 행부터 중복 제거(후보 2): 같은 음식을 반복 스캔한 회원은 10종을 모으려 수천 행을 읽어 상한이 없고, 윈도 함수로 쓰면 어차피 전 행을 본다. 목록 페이지 커서와도 안 맞는다. D1+D2 가 목표에 못 미칠 때만 다시 본다.
  - 기존 인덱스 유지 + 새 인덱스 추가: 쓰기 비용만 늘고 기존 인덱스를 쓸 쿼리가 남지 않는다.

### D3. 남는 한계와 후속 기준

D1+D2 후에도 비용은 회원의 스캔 행 수에 비례한다(인덱스 전용 범위 스캔 4,477엔트리 + 그룹 853개 정렬). 정렬(`max` 는 집계값)은 쿼리 레벨로 없앨 수 없다. 재측정이 SC-001(부하 중 평균 100 ms 미만) 또는 SC-002(무경합 10 ms 미만)에 못 미치면 그 수치를 근거로 읽기 모델(`member_scanned_food`) 후속 태스크를 만든다.

### D4. 측정 순서

1. **기준선(dev, 변경 전)**: 현재 쿼리 `EXPLAIN ANALYZE` + 슬로우 로그 수치. 통계가 낡았으므로 `ANALYZE TABLE scan_history, food` 전후의 플랜을 둘 다 기록해 통계 효과와 쿼리 효과를 분리한다.
2. **후보 비교(로컬 MySQL 8.4)**: 같은 시드를 로컬에 넣고 현재 쿼리 / D1 만 / D1+D2 / 후보 2 를 `EXPLAIN ANALYZE` 로 비교한다. dev 에 인덱스를 손으로 만들지 않는다(Flyway 마이그레이션과 이름이 충돌한다).
3. **최종 판정(dev, 배포 후)**: 같은 시드·같은 k6 부하로 슬로우 로그를 집계한다.

`ANALYZE TABLE` 은 dev 에 대한 쓰기 성격의 명령이라 실행 전에 사용자 확인을 받는다.
