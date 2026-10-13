# Quickstart: 홈 인기 음식 마케팅 목록

## 1. 테스트

```bash
./gradlew :api:test            # 통합(HomeMarketingPopularTest) + OpenApiSnapshotTest + 기존 홈 테스트
```

통합 테스트는 `food_marketing_popular` 에 행을 직접 INSERT 해 풀을 만든다(`HomeTestSeed.reset` 이 food 보다 먼저 비운다). 기존 홈 테스트는 기본 헤더 1.0 이라 영향 없음.

- 1.2 + 풀 {1,2,3} → `popularFoods` = {1, 2, 3}. 풀 12개 → 길이 10. 풀 밖 음식은 안 나옴. 음식 1 FAILED → {2, 3}. 풀 행 DELETED → 제외. 풀 비면 `[]`.
- 1.0·1.1 → 조회 수 집계 순서, 2.0 → 풀(1.2 이상).

## 2. 로컬 실측

```bash
set -a; source ../../../.env; set +a                     # 워크트리엔 .env 없음 — 메인 .env 를 로드
DB_USERNAME=root DB_PASSWORD=root SPRING_PROFILES_ACTIVE=local SERVER_PORT=8081 \
./gradlew :api:bootRun --no-daemon

docker exec kbap-local-mysql mysql -uroot -proot kbap -e "INSERT INTO food_marketing_popular (food_id, status, created_at, updated_at) VALUES (6,'ACTIVE',NOW(6),NOW(6)),(4,'ACTIVE',NOW(6),NOW(6)),(5,'ACTIVE',NOW(6),NOW(6))"
curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.2' | jq '[.payload.popularFoods[].foodId]'   # 6,4,5 의 순열
curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.0' | jq '[.payload.popularFoods[].foodId]'   # 조회 수 집계
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 2.2'      # 400 (미지원 버전)
```

풀을 비우면 1.2 는 빈 목록이다.

## 3. 풀 바꾸기

- prod DB 에서 `food_marketing_popular` 에 INSERT / `status=DELETED` (바스티온 터널 경유, prod 쓰기는 사용자가 직접). 음식 id 는 prod 에서 READY 인지 먼저 확인한다(dev 대조표: `외국인음식/output/K-Food_TOP100_db-match.md`). 다음 요청부터 즉시 반영.
- 끄기: 1.2 를 보내는 앱이 있는 한 풀은 유지돼야 한다(폴백 없음).

## 4. 머지 전 체크

- 기존 홈 테스트(`HomeControllerTest`·`HomeGuestTest`·`HomePopularCacheTest`) 무수정 통과.
- 지식 위키 `home-marketing-popular-config.md`·`api-versioning-policy.md`·`INDEX.md` 갱신.
- Flyway 마이그레이션 1건(테이블 생성) — api 통합 컨텍스트가 `ddl-auto=validate` 로 엔티티↔스키마 정합 검증.
