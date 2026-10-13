# Quickstart: 홈 인기 음식 마케팅 임시 목록

## 1. 테스트

```bash
./gradlew :api:test            # 단위(HomeMarketingPopularPropertiesTest) + 통합(HomeMarketingPopularTest) + OpenApiSnapshotTest 드리프트 가드
```

통합 테스트 전제: 공유 컨텍스트의 테스트 yml 은 **비활성 창**(2000-01-01~01-02)이라 기존 홈 테스트는 집계를 본다. `HomeMarketingPopularTest` 는 실제 빈들로 `HomeService` 를 직접 조립하며 활성 설정(어제~내일, ids 3,1,999,2)과 종료 설정을 바꿔 끼운다.

- 활성: `popularFoods` id = `[3, 1, 2]`(999 제외). 음식 1 을 `FAILED` 로 바꾸면 `[3, 2]`. 전부 FAILED 면 `[]`(집계 폴백 없음).
- 종료: 집계 순서(`[2, 3, 1]` — 음식 2 조회 2건 추가).
- 경계·미설정·역전 창·중복·env 바인딩은 `HomeMarketingPopularPropertiesTest`(단위).

## 2. 로컬 실측

```bash
set -a; source ../../../.env; set +a                     # 워크트리엔 .env 없음 — 메인 .env 를 로드
HOME_MARKETING_POPULAR_STARTS_AT=2026-01-01T00:00:00 \
HOME_MARKETING_POPULAR_ENDS_AT=2099-01-01T00:00:00 \
HOME_MARKETING_POPULAR_FOOD_IDS=6,4,5 \
DB_USERNAME=root DB_PASSWORD=root SPRING_PROFILES_ACTIVE=local SERVER_PORT=8081 \
./gradlew :api:bootRun --no-daemon

curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.0' | jq '.payload.popularFoods[].foodId'   # [6,4,5] (READY 인 것만, 버전 무관)
```

env 를 전부 비우고 띄워 기동이 성공하고 홈이 집계를 내려주는지도 한 번 본다(빈 env 바인딩).

## 3. 캠페인 켜기 / 끄기

- **켜기**: `application-prod.yml` 에 값 커밋(PR 리뷰·이력) 또는 ECS 태스크 정의 env(`kbap-infra`)로 주입 → 배포. 음식 id 는 prod DB 에서 READY 인지 먼저 확인한다(dev 대조표: `외국인음식/output/K-Food_TOP100_db-match.md`, prod 는 별도 확인 — dev 에서 비빔밥·김밥·김치찌개·된장찌개가 FAILED 였다).
- **끄기**: 아무것도 안 해도 `ends-at` 경과 시 자동 폴백. 조기 종료는 `ends-at` 을 앞당기거나 `food-ids` 를 비워 배포.
- 프로덕션 2대는 각자 같은 설정을 읽으므로 인스턴스 간 차이 없음(캐시 아님).

## 4. 머지 전 체크

- 기존 홈 테스트(`HomeControllerTest`·`HomeGuestTest`·`HomePopularCacheTest`) 무수정 통과.
- 지식 위키 `home-marketing-popular-config.md`·`INDEX.md` 갱신.
