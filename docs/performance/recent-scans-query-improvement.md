# 최근 스캔 조회를 어떻게 개선했나 (KB-717)

2026-10-05. 문제 분석은 [recent-scans-query-analysis.md](recent-scans-query-analysis.md), 이 문서는 그 뒤에 무엇을 왜 바꿨는지를 설명한다.

## 한 줄 요약

테이블은 그대로 두고 두 가지를 바꿨다. **쿼리**는 "음식과 조인한 뒤 묶기"에서 "스캔 이력만 먼저 묶고 그 결과에 음식 붙이기"(CTE)로, **인덱스**는 소프트 삭제 컬럼 `status` 를 포함하도록 바꿨다. 응답 계약과 서비스 코드는 그대로다. 변경 후 실행 계획과 수치는 아직 측정 전이다.

## 무엇이 느렸나

시드된 dev(측정 회원 4,477행·음식 853종, READY 음식 4,590개)에서 변경 전 쿼리의 실행 계획은 이랬다.

```
f   ref  idx_food_content_status   rows=4590  Using index condition; Using where; Using temporary; Using filesort
sh  ref  idx_scan_history_member_food_recent (const, f.id)  rows=1  Using where
```

세 가지 비용이 겹쳐 있었다.

| # | 원인 | 왜 비싼가 |
|---|---|---|
| 1 | 음식이 드라이빙 테이블 | READY 음식 4,590개를 먼저 훑고 음식마다 스캔 인덱스를 찍는다. 회원이 본 음식은 853종뿐인데 전체 음식 수만큼 일한다 |
| 2 | 인덱스가 커버링이 아님 | 인덱스 `(member_id, food_id, created_at)` 에 `status` 가 없다. `status = 'ACTIVE'` 를 확인하려고 스캔 행마다 본 테이블을 읽는다 |
| 3 | 임시 테이블에 음식 행 전체 | `select f.* … group by f.id` 라 그룹 853개가 각각 JSON 컬럼 3개와 긴 설명을 들고 임시 테이블에 들어가 정렬된다 |

`limit 10` 은 이 모든 일이 끝난 뒤에야 적용된다.

## 바꾼 것 1 — 쿼리: 선집계 후 결합

### 변경 전

```sql
select f.*, max(sh.created_at)
from scan_history sh join food f on f.id = sh.food_id
where sh.member_id = ? and sh.status = 'ACTIVE'
  and f.status = 'ACTIVE' and f.content_status = 'READY'
group by f.id
order by max(sh.created_at) desc
limit 10
```

### 변경 후

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
order by r.scanned_at desc
limit 10
```

코드에서는 JPQL 의 `with` 절로 썼고, Hibernate 가 내는 SQL 이 위 형태 그대로인 것을 테스트 로그로 확인했다.

### 이게 왜 나은가

- **음식이 드라이빙 테이블이 될 수 없다(원인 1).** CTE 안에는 `scan_history` 만 있다. 음식은 CTE 결과의 `food_id` 로 기본 키 조회로만 붙는다. 조인 순서 힌트 없이 구조가 순서를 고정한다. 일하는 양이 "전체 READY 음식 수"에서 "회원이 본 음식 수"로 바뀐다.
- **묶고 정렬하는 대상이 좁아진다(원인 3).** 임시 테이블 한 행이 음식 행 전체에서 `(food_id, scanned_at)` 두 컬럼으로 줄어든다. 853개를 정렬해도 수십 KB 다.
- **넓은 음식 행은 필요한 만큼만 읽는다.** READY 여부 확인은 기본 키 조회로 하고, JSON 을 포함한 전체 행이 결과로 나가는 건 최종 10개다.

### 상위 N개만 먼저 자르지 않은 이유

"스캔 이력에서 최근 30개만 뽑아 음식과 결합"하면 더 싸 보인다. 그러나 그 30개 중 21개 이상이 비공개 음식이면 공개 음식이 더 있는데도 결과가 10개에 못 미친다. 853번의 기본 키 조회는 충분히 싸므로 전부 결합하고 `limit` 을 맨 뒤에 뒀다.

## 바꾼 것 2 — 인덱스: `status` 포함

```sql
alter table scan_history
    drop index idx_scan_history_member_food_recent,                 -- (member_id, food_id, created_at)
    add index idx_scan_history_member_status_food_recent
        (member_id, status, food_id, created_at);
```

### 이게 왜 나은가

- **CTE 가 인덱스만으로 끝난다(원인 2).** CTE 가 쓰는 컬럼 넷(`member_id`, `status`, `food_id`, `created_at`)이 전부 인덱스에 있다. 스캔 행마다 본 테이블을 읽던 4,477번의 조회가 없어진다.
- **묶는 순서가 인덱스 순서다.** `member_id` 와 `status` 가 상수로 고정되면 인덱스는 `food_id` 순으로 정렬돼 있고, 같은 음식 안에서는 `created_at` 순이다. 음식별 마지막 스캔 시각은 각 음식 구간의 마지막 엔트리다.

### `status` 를 쿼리에서 빼지 않고 인덱스에 넣은 이유

모든 엔티티는 `@SQLRestriction("status = 'ACTIVE'")` 를 상속한다. JPQL 은 이 조건을 자동으로 붙이므로 쿼리에서 뺄 수 없다. 조건이 항상 붙는다면 인덱스가 그 컬럼을 갖는 쪽이 맞다.

### 다른 조회와 배포 중 공존

같은 인덱스를 쓰는 조회는 넷이다(내 스캔 목록 페이지, 내 스캔 검색, 음식별 마지막 스캔 시각, 스캔 여부 확인). 넷 다 `member_id = ? and status = 'ACTIVE'` 조건을 가지므로 새 인덱스를 그대로 또는 더 잘 탄다. 블루/그린 배포 중 구 코드의 쿼리도 같은 이유로 새 인덱스 위에서 동작한다. `member_id` 가 맨 앞이라 회원 외래 키가 요구하는 인덱스 조건도 유지된다.

## 바꾸지 않은 것

- **응답 계약**: READY 만, 음식별 한 번, 마지막 스캔 시각 내림차순, 최대 10개. 그대로다.
- **코드 표면**: 리포지토리 메서드 시그니처, 서비스, 컨트롤러 모두 그대로다. 홈의 SQL 문 수도 같다.
- **내 스캔 목록 페이지·검색 쿼리**: 같은 구조지만 이 작업 범위 밖이다. 인덱스 변경의 덕만 본다.

## 테스트

기존 리포지토리 테스트 5개 시나리오에 세 가지를 더했다. 쿼리를 바꾸기 전에 먼저 추가해 변경 전 쿼리에서도 통과하는 것을 확인했다.

- 삭제된 스캔 행만 남은 음식은 나오지 않고, 살아 있는 행의 시각으로 정렬된다.
- 삭제된 음식은 나오지 않는다.
- 반환되는 마지막 스캔 시각이 그 음식의 가장 최근 행 시각이다.

`:common`, `:api` 전체 테스트가 통과한다.

## 남는 한계

변경 후에도 비용은 회원의 스캔 행 수에 비례한다.

- 인덱스 전용이지만 회원의 엔트리 4,477개를 읽는다.
- 정렬 기준인 마지막 스캔 시각은 집계 결과라 어떤 인덱스도 정렬을 대신하지 못한다. 그룹 853개의 정렬은 남는다.

"10개를 보여 주려고 회원의 전 이력을 본다"는 구조 자체는 쿼리 레벨로 없앨 수 없다. 없애려면 회원×음식당 한 행인 읽기 모델(`member_scanned_food`)이 필요하다. 그러면 인덱스 순서로 10행 남짓만 읽고 끝난다.

| 방식 | 읽는 양 | 정렬 | 스캔 저장 경로 |
|---|---|---|---|
| 변경 전 | 전체 READY 음식 + 스캔 행 본 테이블 | 넓은 임시 테이블 | 변화 없음 |
| 이 작업 (CTE + 인덱스) | 회원의 인덱스 엔트리 전부 | 좁은 임시 테이블 | 변화 없음 |
| 읽기 모델 | 10행 남짓 | 없음 | 스캔 저장 시 upsert 추가 |

읽기 모델은 이 작업에서 만들지 않았다. 재측정이 목표에 못 미치면 그 수치를 근거로 후속 태스크로 간다.

## 검증 방법

[`scripts/perf/recent-scans-candidates.sql`](../../scripts/perf/recent-scans-candidates.sql) 에 회원 3 기준 `EXPLAIN ANALYZE` 문이 블록별로 있다.

| 블록 | 내용 | 볼 것 |
|---|---|---|
| A | 변경 전 쿼리 | 기준선 |
| B | CTE, 기존 인덱스 | 음식이 기본 키로만 붙는가 |
| C | CTE + 새 인덱스 (이 작업의 최종 형태) | `scan_history` 가 Using index 인가, 범위 밖 조회가 느려지지 않았는가 |
| D | 윈도 함수로 최신 행부터 중복 제거 | 전 행을 읽는다면 C 대비 이득 없음 |
| E | 읽기 모델 | 검사 행 수가 10 근처인가 |

C·D·E 는 인덱스나 임시 테이블을 만들므로 로컬에서만 실행한다. dev 는 시드 후 통계가 갱신되지 않아(17만 행을 약 1만 행으로 본다) 비교 전에 `ANALYZE TABLE scan_history, food` 가 필요하다.

최종 판정은 dev 배포 후 같은 시드·같은 부하(k6 동시 1,000명, 약 70 rps)의 RDS 슬로우 로그로 한다.

| 조건 | 변경 전 | 목표 | 변경 후 |
|---|---|---|---|
| 70 rps 부하 중 평균 | 2,803 ms | 100 ms 미만 | 측정 전 |
| 무경합 | 21 ms | 10 ms 미만 | 측정 전 |
