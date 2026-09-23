# Quickstart: 음식 상세 조회 횟수 비동기 로그 적재

## 자동 검증

```bash
./gradlew :api:test
```

Kotest 는 Gradle `--tests` 필터를 무시하므로 모듈 전체를 돈다. 확인 대상: `FoodViewLogTest`(신규) 전 시나리오 통과 + `ModuleBoundaryTest`(arch) 통과 + 기존 `FoodDetail*Test` 회귀 없음.

## 수동 검증 (로컬)

1. MySQL·Redis 컨테이너와 `.env` 를 갖추고 api 를 띄운다.

   ```bash
   set -a; source .env; set +a
   SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
   ```

2. 부팅 로그에서 Flyway 가 `food_view_log_table` 마이그레이션을 적용했는지 본다.
3. 상세 조회를 회원·게스트로 각각 호출한다.

   ```bash
   curl -s -H 'X-API-Version: 1.0' -H "Authorization: Bearer $ACCESS_TOKEN" \
     'http://localhost:8080/api/foods/1?lang=ko' | head -c 200
   curl -s -H 'X-API-Version: 1.0' 'http://localhost:8080/api/foods/1?lang=ko' | head -c 200
   ```

4. 행을 확인한다.

   ```sql
   SELECT id, food_id, member_id, created_at FROM food_view_log ORDER BY id DESC LIMIT 5;
   ```

   기대: 2행 — 첫 행 `member_id` = 토큰 회원, 둘째 행 `member_id` NULL.

5. 없는 음식(`/api/foods/999999`)을 호출하면 400(`FOOD-001`)이고 행이 늘지 않는다.
6. 실패 무영향: `food_view_log` 를 `RENAME TABLE` 로 잠시 치우고 상세 조회 → 응답은 200, 서버 로그에 `조회 이력 저장 실패` error 1줄이 `task-N` 스레드에서 `[reqId=<요청 id>]` 를 달고 찍힌다(MDC 전파 확인). 테이블을 되돌린다.
7. 로컬 MySQL 의 `kbap` 사용자 비밀번호가 `.env` 와 다르면(볼륨 최초 init 때만 생성) `DB_USERNAME=root DB_PASSWORD=root` 를 환경변수로 덮어 bootRun 한다.
