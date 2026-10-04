# Quickstart: 측정·검증 절차

## 1. 기준선 (dev, 변경 전)

```bash
ssh -fN kbap-dev-bastion            # 터널 13307
mysql -h 127.0.0.1 -P 13307 -u <user> -p kbap-dev
```

```sql
EXPLAIN ANALYZE
select f.*, max(sh.created_at)
from scan_history sh join food f on f.id = sh.food_id
where sh.member_id = 3 and sh.status = 'ACTIVE'
  and f.status = 'ACTIVE' and f.content_status = 'READY'
group by f.id order by max(sh.created_at) desc limit 10;
```

기록할 것: 플랜, 검사 행 수, Using temporary / filesort 유무, 실행 시간. `ANALYZE TABLE scan_history, food;` 는 사용자 확인 후 실행하고 전후 플랜을 둘 다 남긴다.

## 2. 후보 비교 (로컬)

로컬 MySQL(`kbap-local-mysql`, root/root)에 회원 id 3 이 있는지 확인하고 `scripts/perf/seed-home-load.sql` 을 넣는다. 비교 전에 `SHOW INDEX FROM scan_history` 로 인덱스 상태를 확인한다. 이 브랜치의 마이그레이션이 이미 적용된 DB 면 구 인덱스로 먼저 되돌려야 현재·D1 후보가 기준선이 된다(절차는 `scripts/perf/recent-scans-candidates.sql` 의 사전 준비·마무리 블록). 아래 넷을 `EXPLAIN ANALYZE` 로 비교해 표로 만든다.

| 후보 | 쿼리 | 인덱스 |
|---|---|---|
| 현재 | 조인 후 group by | (member_id, food_id, created_at) |
| D1 | 선집계 후 PK 결합 | 그대로 |
| D1+D2 | 선집계 후 PK 결합 | (member_id, status, food_id, created_at) |
| 후보 2 | 최신 행부터 중복 제거 | (member_id, status, created_at desc, food_id) |

끝나면 `scripts/perf/cleanup-home-load.sql` 로 지운다.

## 3. 테스트

```bash
./gradlew :api:test
```

Kotest 는 `--tests` 필터를 무시하므로 모듈 전체를 돌린다. `ScanHistoryRepositoryTest` 와 홈 통합 테스트가 통과해야 한다.

## 4. 최종 판정 (dev, 배포 후)

```bash
export ACCESS_TOKEN="$(python3 k6/mint-token.py 3 2)"
k6 run -e BASE_URL=https://dev.kbap.site -e ACCESS_TOKEN="$ACCESS_TOKEN" ~/Desktop/1만명-tomcat스레드.js
```

VPN 을 끄고 실행한다. CloudWatch `/aws/rds/instance/kbap-db-devstg/slowquery` 에서 최근 스캔 쿼리를 정규화해 평균·p95 를 집계한다.

| 조건 | 기준선 | 목표 |
|---|---|---|
| 70 rps 부하 중 평균 | 2,803 ms | 100 ms 미만 |
| 무경합 | 21 ms | 10 ms 미만 |

미달이면 한계 수치와 근거를 적어 읽기 모델 후속 Jira 태스크를 만든다.
