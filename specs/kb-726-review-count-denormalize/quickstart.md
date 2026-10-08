# Quickstart: 홈 리뷰 인기 음식 — 음식별 리뷰 수 역정규화

**Plan**: [plan.md](plan.md) | **Jira**: [KB-726](https://simhani1.atlassian.net/browse/KB-726)

## 1. 자동 테스트 (구현 중·PR 전)

```bash
./gradlew :common:test   # FoodJpaRepositoryTest — findMostReviewed 정렬·제외, 증감이 version 을 안 올림
./gradlew :api:test      # ReviewControllerTest·AdminReportControllerTest·HomeControllerTest
./gradlew build          # 전체 — Flyway + ddl-auto=validate 로 엔티티↔스키마 정합 확인
```

Kotest 는 `--tests` 필터를 무시하므로 모듈 단위로 돌린다.

## 2. 로컬 실행 검증

워크트리에는 `.env` 가 없다 — 메인 체크아웃의 `.env` 를 `set -a; source ../../.env; set +a` 로 읽고 `DB_USERNAME=root DB_PASSWORD=root` 로 덮어 `./gradlew :api:bootRun` 한다(메모리 "워크트리 bootRun 환경 함정").

1. 부팅 로그에서 `V2026.10.08.*__food_review_count` 마이그레이션 적용 확인.
2. 드리프트 점검 — 0건이어야 한다:
   ```sql
   SELECT f.id, f.review_count, COALESCE(x.cnt, 0) AS actual
   FROM food f
   LEFT JOIN (SELECT food_id, COUNT(*) cnt FROM food_review WHERE status = 'ACTIVE' GROUP BY food_id) x ON x.food_id = f.id
   WHERE f.review_count <> COALESCE(x.cnt, 0);
   ```
3. 실행 계획 — 인덱스 순서 읽기 확인(`Using filesort` 가 없어야 한다):
   ```sql
   EXPLAIN SELECT f.* FROM food f
   WHERE f.status = 'ACTIVE' AND f.content_status = 'READY' AND f.review_count > 0
   ORDER BY f.review_count DESC, f.id DESC LIMIT 10;
   ```
4. 리뷰 작성(`POST /api/reviews`) → `SELECT review_count FROM food WHERE id = ?` 가 1 증가, 삭제(`DELETE /api/reviews/{id}`) → 1 감소. `GET /api/home?lang=en` 의 `mostReviewedFoods` 순서가 `review_count desc, id desc`.

## 3. 배포 절차 (dev → prod)

1. 신 리비전 기동 시 Flyway 가 컬럼·인덱스·백필을 적용한다. 구 리비전은 새 컬럼을 모르고 그대로 동작한다(가산 변경).
2. **구 리비전이 완전히 내려간 뒤** 백필 문장을 한 번 더 실행한다 — 겹침 구간에 구 코드가 만든 리뷰는 카운터에 반영되지 않았기 때문이다. 문장은 절대값 대입이라 재실행이 안전하다:
   ```sql
   UPDATE food f
   LEFT JOIN (SELECT food_id, COUNT(*) AS cnt FROM food_review WHERE status = 'ACTIVE' GROUP BY food_id) x ON x.food_id = f.id
   SET f.review_count = COALESCE(x.cnt, 0);
   ```
   dev/prod RDS 는 바스티온 터널 선행(메모리 "RDS 바스티온 터널·MCP"). prod 쓰기는 사용자가 직접 수행한다.
3. 2 의 드리프트 점검 SQL 로 0건 확인.

## 4. dev 부하 테스트 3차 (SC-005, 사용자 수행)

- 조건: 홈 동시 1,000명, KB-725 캐시 적용 상태(2차와 동일). VPN 끄기(메모리 "부하 테스트 전 VPN 끄기").
- 확인: RDS 슬로우 로그 상위에서 `food_review … group by` 쿼리가 사라짐, 홈 1건당 DB 시간 약 0.1초(2차 1.44초), 성공률·CPU 를 Notion 부하 테스트 페이지에 기록.

## 5. 운영 중 드리프트가 의심될 때

2 의 점검 SQL 로 불일치 음식을 찾고, 3-2 의 백필 문장을 재실행한다. 주기 배치는 없다(Jira 결정).
