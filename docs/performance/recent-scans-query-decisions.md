# 최근 스캔 조회 — 쿼리·인덱스 결정 기록 (KB-717)

2026-10-05. 문제 해결 과정을 정리할 때 쓰는 원자료다. 결정마다 "무엇을 골랐나 / 근거 / 버린 대안 / 확인 상태"를 적는다. 확인 상태는 **실측**(dev 에서 본 것), **검증**(테스트로 확인), **추론**(아직 측정하지 않은 예상)으로 구분한다.

관련 문서: 문제 분석 [recent-scans-query-analysis.md](recent-scans-query-analysis.md), 개선 설명 [recent-scans-query-improvement.md](recent-scans-query-improvement.md), 비교용 SQL [`scripts/perf/recent-scans-candidates.sql`](../../scripts/perf/recent-scans-candidates.sql), PR #377.

## 0. 출발점

### 요구

"이 회원이 최근에 본 음식 10개, 음식별로 한 번씩, 마지막 스캔 시각 순, READY 음식만." 스캔 이력은 스캔할 때마다 메뉴 항목당 한 행이 쌓이는 로그라 같은 음식이 여러 행이다.

### 제약

- 테이블 추가·컬럼 변경 없음. 쿼리와 인덱스만 바꾼다(Jira 정의).
- 홈 응답 계약, 리포지토리 시그니처, 홈 SQL 문 수 유지.
- 블루/그린 배포 중 구 코드가 새 스키마 위에서 돈다.

### 변경 전 쿼리와 실행 계획 (실측, 시드된 dev · MySQL 8.4.9)

```sql
select f.*, max(sh.created_at)
from scan_history sh join food f on f.id = sh.food_id
where sh.member_id = ? and sh.status = 'ACTIVE'
  and f.status = 'ACTIVE' and f.content_status = 'READY'
group by f.id order by max(sh.created_at) desc limit 10
```

```
f   ref  idx_food_content_status  rows=4590  Using index condition; Using where; Using temporary; Using filesort
sh  ref  idx_scan_history_member_food_recent (const, f.id)  rows=1  Using where
```

데이터: scan_history 171,667행, 측정 회원 4,477행·853종, food 5,213개 중 READY·ACTIVE 4,590개.

### 실행 계획에서 읽은 세 원인

| # | 원인 | 근거 |
|---|---|---|
| 1 | 음식이 드라이빙 테이블 | 첫 줄이 `f`, rows=4590. 회원이 본 음식은 853종인데 전체 READY 음식만큼 일한다 |
| 2 | 스캔 인덱스가 커버링이 아님 | `sh` 줄이 `Using where` 이고 `Using index` 가 아니다. `status` 가 인덱스에 없어 본 테이블을 읽는다 |
| 3 | 임시 테이블에 음식 행 전체 | `select f.* … group by f.id` + `Using temporary; Using filesort`. 그룹 한 행이 JSON 컬럼 3개를 든다 |

## 1. 쿼리 결정 — 선집계 후 결합, CTE 로 표기

### 고른 것

```sql
with recent (food_id, scanned_at) as (
    select sh.food_id, max(sh.created_at)
    from scan_history sh
    where sh.member_id = ? and sh.status = 'ACTIVE'
    group by sh.food_id
)
select f.*, r.scanned_at
from recent r join food f on f.id = r.food_id
where f.status = 'ACTIVE' and f.content_status = 'READY'
order by r.scanned_at desc limit 10
```

### 결정은 두 층이다

**(가) 형태: "조인 후 묶기"를 "묶은 뒤 조인"으로.** 실질적인 결정은 이것이다.

- 원인 1 제거: 집계 블록 안에 `scan_history` 만 있으므로 음식은 집계 결과의 `food_id` 로 PK 조회로만 붙는다. 옵티마이저가 조인 순서를 뒤집을 여지가 구조적으로 없다. 힌트가 필요 없다.
- 원인 3 제거: 묶고 정렬하는 행이 `(food_id, scanned_at)` 두 컬럼이다.
- 정렬이 조인보다 앞선다: 정렬 키가 집계 결과 쪽에만 있으므로 MySQL 은 집계 결과를 먼저 정렬하고 그 순서대로 음식을 붙이다가 10개가 차면 멈출 수 있다. 음식 PK 조회 횟수가 853이 아니라 10 근처(READY 탈락분만큼 추가)가 된다. (추론 — dev EXPLAIN 에서 `Using filesort` 가 파생 테이블 줄에 붙어 있는 것이 이 순서를 뜻한다. EXPLAIN ANALYZE 의 실제 행 수로 확인 필요)

**(나) 표기: 파생 테이블 대신 CTE.** 성능 결정이 아니라 가독성 결정이다.

- MySQL 8 에서 한 번만 참조되는 비재귀 CTE 는 FROM 절 서브쿼리(파생 테이블)와 같은 방식으로 처리된다. 실행 계획이 같다. (실측 — dev 에서 CTE 형태와 파생 테이블 형태의 EXPLAIN 이 같은 세 줄이었다)
- CTE 로 쓰면 "먼저 회원의 음식별 마지막 스캔 시각을 구한다 → 그걸 음식과 맞춘다"는 두 단계가 위에서 아래로 읽힌다.
- Hibernate 7.4 의 JPQL 이 `with` 절을 지원한다. Hibernate 가 낸 SQL 이 위 형태 그대로다. (검증 — 테스트 로그)

### 네이티브가 아니라 JPQL 로 쓴 이유

- 반환 프로젝션 `RecentScannedFood(food: Food, scannedAt)` 를 그대로 쓸 수 있다. 네이티브로 엔티티 + 스칼라를 한 번에 받으려면 매핑을 따로 만들거나, id 만 받고 음식을 한 번 더 조회해야 한다(SQL 문 1개 증가 — KB-654 에서 줄여 놓은 왕복 수가 다시 는다).
- 소프트 삭제 조건을 `@SQLRestriction` 이 양쪽 테이블에 자동으로 붙인다. 손으로 쓰다 빠뜨릴 일이 없다.
- `Pageable` 로 받는 `limit` 을 그대로 쓴다.

### 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 상위 N개(예: 30)만 먼저 자르고 음식과 결합 | N개 중 비공개 음식이 N-10개를 넘으면 공개 음식이 더 있어도 결과가 10개에 못 미친다. 계약 위반 가능성을 N 튜닝으로 덮는 구조다. 위 (가)의 "정렬 후 조인하다 멈춤"이 같은 효과를 정확하게 낸다 |
| 조인 순서 힌트 (`STRAIGHT_JOIN`, `JOIN_ORDER`) | JPQL 로 쓸 수 없고, 옵티마이저 판단을 힌트로 고정하면 데이터 분포가 바뀔 때 다시 손봐야 한다. 형태 변경이 같은 결과를 구조로 보장한다 |
| 윈도 함수로 최신 행부터 음식 중복 제거 | `row_number() over (partition by food_id order by created_at desc)` 는 회원의 전 행에 번호를 매긴 뒤 거른다. 읽는 양이 집계와 같고 정렬이 하나 더 붙는다. (추론 — 비교용 SQL 의 D 블록으로 확인 가능) |
| 앱에서 최신 행부터 읽다 10종 모이면 멈춤 | 같은 음식을 반복 스캔한 회원은 10종을 모으려고 수천 행을 읽는다. 상한이 없고, 페이지 크기 추정이 필요하다 |
| `select f.*` 를 홈 카드 컬럼으로 좁힘 | 반환 타입이 엔티티가 아니게 돼 서비스·뷰 조립을 고쳐야 한다. 선집계로 넓은 행이 최종 10개로 줄어 남는 이득이 작다 |
| 읽기 모델 `member_scanned_food` | 이 작업의 제약(테이블 추가 없음) 밖이다. 쿼리 레벨의 한계가 수치로 확인되면 후속 태스크로 간다. 4절 참고 |

## 2. 인덱스 결정 — `(member_id, status, food_id, created_at)`

### 고른 것

```sql
alter table scan_history
    drop index idx_scan_history_member_food_recent,          -- (member_id, food_id, created_at)
    add index idx_scan_history_member_status_food_recent (member_id, status, food_id, created_at);
```

### 설계 순서

**① 쿼리가 쓰는 컬럼을 전부 모은다 → 커버링.** 집계 블록은 `member_id`, `status`, `food_id`, `created_at` 넷만 쓴다. 넷이 다 인덱스에 있으면 본 테이블을 읽지 않는다. 기존 인덱스는 `status` 하나가 빠져서 스캔 행마다(4,477번) 본 테이블을 읽었다(원인 2).

**② 컬럼 순서는 "등치 → 그룹 → 집계 대상".**

| 위치 | 컬럼 | 쿼리에서의 역할 | 이 자리에 두는 이유 |
|---|---|---|---|
| 1 | `member_id` | `= ?` | 가장 선택도가 높은 등치 조건. 회원 FK 가 요구하는 "member_id 가 맨 앞인 인덱스"도 겸한다 |
| 2 | `status` | `= 'ACTIVE'` | 등치 조건이라 앞에 둬야 그 뒤 컬럼의 정렬이 유지된다 |
| 3 | `food_id` | `group by` | 앞 둘이 상수면 인덱스가 `food_id` 순이다. 같은 음식의 엔트리가 연속으로 놓여 임시 테이블 없이 묶을 수 있다 |
| 4 | `created_at` | `max()` | 같은 음식 구간 안에서 시각 순이다. 마지막 스캔 시각은 구간의 마지막 엔트리다 |

**③ `status` 를 맨 뒤가 아니라 두 번째에 둔 이유.** `(member_id, food_id, created_at, status)` 도 커버링은 된다. 그러나 `status` 가 뒤에 있으면 음식 구간 안에 ACTIVE 와 DELETED 엔트리가 섞여, "구간의 마지막 엔트리 = 마지막 스캔 시각"이 성립하지 않는다. 엔트리마다 `status` 를 걸러야 한다. 등치 조건을 앞에 두면 인덱스의 해당 범위가 전부 ACTIVE 다.

**④ `status` 의 낮은 카디널리티는 문제가 아니다.** 값이 둘뿐인 컬럼을 인덱스 선두에 두면 쓸모없지만, 여기서는 `member_id` 뒤의 두 번째 등치 컬럼이다. 선택도를 높이려는 것이 아니라 커버링과 정렬 유지를 위한 자리다.

**⑤ `status` 조건을 쿼리에서 빼는 선택지는 없다.** 모든 엔티티가 `@SQLRestriction("status = 'ACTIVE'")` 를 상속해 JPQL 에 자동으로 붙는다. 조건이 항상 붙는다면 인덱스가 그 컬럼을 갖는 쪽이 맞다.

**⑥ 추가가 아니라 교체.** 기존 인덱스를 쓰던 조회가 전부 새 인덱스로 옮겨 가므로 구 인덱스를 남기면 쓰기 비용만 는다. 인덱스 수는 그대로이고 엔트리 폭만 `status` 만큼 늘어난다.

### 같은 인덱스를 쓰는 다른 조회 (추론 — 비교용 SQL C 블록에 확인용 EXPLAIN 있음)

| 조회 | 조건 | 새 인덱스에서 |
|---|---|---|
| 내 스캔 목록 페이지 `findScannedFoodPageIds` | `member_id = ? and status = 'ACTIVE'`, `group by food_id`, `max(created_at)` | 같은 접두를 타고 커버링이 된다 |
| 내 스캔 검색 `findScannedFoodIds` | 위와 같음 + 음식명 LIKE | 같음 |
| 마지막 스캔 시각 `findLastScannedAt` | `member_id = ? and food_id = ? and status = 'ACTIVE'`, `max(created_at)` | 세 컬럼 등치 후 마지막 엔트리 하나 |
| 스캔 여부 `existsByMemberIdAndFoodId` | `member_id = ? and food_id = ?` + 자동 `status` | 세 컬럼 등치 |

네 조회 모두 `member_id`·`status` 등치를 가지므로 기존보다 나빠질 경로가 없다.

### 배포 중 공존

구 코드의 쿼리(조인 후 묶기)도 `member_id = ? and status = 'ACTIVE'` 를 가지므로 새 인덱스를 탄다. 한 ALTER 에서 삭제와 생성을 같이 해 인덱스가 없는 순간이 없다(선례: `V2026.09.30.14.56.23`).

### 버린 대안

| 대안 | 버린 이유 |
|---|---|
| `(member_id, status, created_at desc, food_id)` | "최신 행부터 읽기"용이다. 이 순서에서는 같은 음식의 엔트리가 흩어져 `group by food_id` 에 임시 테이블이 다시 필요하다. 윈도 함수·앱 중복 제거 방식과 짝인데 그 방식을 버렸다. 목록 페이지 커서 `(last_scanned_at, food_id)` 와도 맞지 않는다 |
| 기존 인덱스 유지 + 새 인덱스 추가 | 기존 인덱스를 쓸 쿼리가 남지 않는다 |
| 기존 인덱스 유지, 쿼리만 변경 | dev 에서 해 본 상태다. 아래 3절 |

## 3. 지금까지 확인된 것과 안 된 것

### 쿼리만 바꾼 상태 (실측, dev — 인덱스는 기존)

```
<derived>  ALL     rows=917   Using where; Using filesort
f          eq_ref  PRIMARY (r.food_id)  Using where
sh         ref     idx_scan_history_member_food_recent (const)  rows=9172  Using where
```

- 음식이 PK 로만 붙는다 → 원인 1 해소.
- `sh` 가 여전히 `Using where` → 원인 2 그대로. 본 테이블 4,477번 읽기가 남아 있다.
- 클라이언트 기준 실행 시간은 기존 쿼리와 비슷했다(약 449 ms). 이 값은 터널과 결과 전송을 포함한다. 서버가 기록한 시간(`performance_schema`)은 수십 ms 대다.
- 결론: **쿼리 형태만으로는 체감 차이가 없다. 남은 비용의 대부분이 원인 2이고, 그건 인덱스가 해결한다.**

### 아직 측정하지 않은 것

- 인덱스 교체 후의 실행 계획(`sh` 가 `Using index` 가 되는지, 임시 테이블이 사라지는지).
- 인덱스 교체 후 무경합·70 rps 부하 중 실행 시간.
- dev 는 시드 후 통계가 갱신되지 않았다(17만 행을 약 1만 행으로 본다). `ANALYZE TABLE` 전후로 플랜이 달라질 수 있다.

### 판정 기준

| 조건 | 변경 전 | 목표 |
|---|---|---|
| 70 rps 부하 중 평균 | 2,803 ms | 100 ms 미만 |
| 무경합 | 21 ms | 10 ms 미만 |

## 4. 쿼리 레벨의 한계

선집계와 커버링 인덱스로도 없어지지 않는 비용이 둘 있다.

- 회원의 인덱스 엔트리 전부를 읽는다(4,477개). 스캔이 쌓일수록 늘고 상한이 없다.
- 정렬 기준인 마지막 스캔 시각은 집계 결과다. 저장된 값이 아니므로 어떤 인덱스도 정렬을 대신하지 못한다. 그룹 수(853)만큼의 정렬이 남는다.

"10개를 보여 주려고 회원의 전 이력을 본다"는 구조는 로그 테이블에서 직접 답을 구하는 한 그대로다. 없애려면 답의 모양으로 미리 저장해야 한다.

```
member_scanned_food (member_id, food_id) PK, last_scanned_at, scan_count
index (member_id, last_scanned_at desc, food_id)
```

회원×음식당 한 행이면 정렬 키가 저장된 컬럼이 되고, 인덱스 순서로 10행 남짓만 읽고 끝난다. 대가로 스캔 저장 경로에 upsert 가 하나 붙고 백필이 필요하다. 인덱스 교체 후에도 목표에 못 미치면 이 수치를 근거로 후속 태스크로 간다.
