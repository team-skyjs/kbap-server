# Quickstart: 홈 인기 음식 마케팅 목록

## 1. 테스트

```bash
./gradlew :api:test            # 단위(HomeMarketingPopularPropertiesTest) + 통합(HomeMarketingPopularTest) + OpenApiSnapshotTest
```

통합 테스트 전제(`api/src/test/resources/application.yml`): `food-ids: 1,2,3,4,5,6,7,8,9,10,11,12,999`. 기존 홈 테스트는 기본 헤더 1.0 이라 영향 없음.

- 1.2 + 음식 1~3 시드 → `popularFoods` = {1, 2, 3}(999 제외). 12개 시드 → 길이 10, 전부 1..12. 음식 1 FAILED → {2, 3}. 전부 FAILED → `[]`.
- 1.0·1.1 → 조회 수 집계 순서, 2.0 → 풀(1.2 이상).

## 2. 로컬 실측

```bash
set -a; source ../../../.env; set +a                     # 워크트리엔 .env 없음 — 메인 .env 를 로드
HOME_MARKETING_POPULAR_FOOD_IDS=6,4,999999,5,6 \
DB_USERNAME=root DB_PASSWORD=root SPRING_PROFILES_ACTIVE=local SERVER_PORT=8081 \
./gradlew :api:bootRun --no-daemon

curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.2' | jq '[.payload.popularFoods[].foodId]'   # 6,4,5 의 순열(미존재·중복 제외)
curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.0' | jq '[.payload.popularFoods[].foodId]'   # 조회 수 집계
curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 2.2'      # 400 (미지원 버전)
```

env 를 비우고 띄워 기동이 성공하는지도 본다(1.2 은 빈 목록).

## 3. 풀 바꾸기

- `application-prod.yml` 에 값 커밋(PR 이력) 또는 ECS 태스크 정의 env(`kbap-infra`) → 배포. 음식 id 는 prod DB 에서 READY 인지 먼저 확인한다(dev 대조표: `외국인음식/output/K-Food_TOP100_db-match.md`).
- 끄기: 1.2 을 보내는 앱이 있는 한 풀은 유지돼야 한다(폴백 없음). 기능 자체를 내리려면 코드 변경.

## 4. 머지 전 체크

- 기존 홈 테스트(`HomeControllerTest`·`HomeGuestTest`·`HomePopularCacheTest`) 무수정 통과.
- 지식 위키 `home-marketing-popular-config.md`·`api-versioning-policy.md`·`INDEX.md` 갱신.
