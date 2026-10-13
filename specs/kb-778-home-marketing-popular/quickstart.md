# Quickstart: 홈 인기 음식 마케팅 임시 목록

## 1. 테스트

```bash
./gradlew :api:test            # 단위(HomeMarketingPopularPropertiesTest) + 통합(HomeMarketingPopularTest) + OpenApiSnapshotTest 드리프트 가드
```

통합 테스트 전제(`api/src/test/resources/application.yml`):

```yaml
kbap:
  home:
    marketing-popular:
      starts-at: 2000-01-01T00:00:00
      ends-at: 2100-01-01T00:00:00
      food-ids: 3,1,999,2
```

- 통합 테스트는 항상 기간 안: 2.1 응답 `popularFoods` id = `[3, 1, 2]`(999 제외). 음식 1 을 `FAILED` 로 바꾸면 `[3, 2]`.
- 기간 밖·경계·미설정·역전 창은 `HomeMarketingPopularPropertiesTest`(단위)로 고정.
- 기존 홈 테스트(`HomeControllerTest`·`HomeGuestTest`·`HomePopularCacheTest`)는 기본 헤더 1.0 이라 영향 없음(검증 본문 무수정).

## 2. 로컬 실측

```bash
set -a; source ../../../.env; set +a                     # 워크트리엔 .env 없음 — 메인 .env 를 로드
HOME_MARKETING_POPULAR_STARTS_AT=2026-01-01T00:00:00 \
HOME_MARKETING_POPULAR_ENDS_AT=2099-01-01T00:00:00 \
HOME_MARKETING_POPULAR_FOOD_IDS=6,4,5 \
DB_USERNAME=root DB_PASSWORD=root SPRING_PROFILES_ACTIVE=local SERVER_PORT=8081 \
./gradlew :api:bootRun --no-daemon

curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 2.1' | jq '.payload.popularFoods[].foodId'   # [6,4,5] (READY 인 것만)
curl -s 'http://localhost:8081/api/home?lang=en' -H 'X-API-Version: 1.0' | jq '.payload.popularFoods[].foodId'   # 조회 수 집계
```

env 를 전부 비우고 띄워 기동이 성공하고 2.1 이 집계를 내려주는지도 한 번 본다(빈 env 바인딩).

Swagger: `http://localhost:8081/swagger-ui/index.html` 그룹 드롭다운에 `X-API-Version 2.1` 이 있고 홈 오퍼레이션이 실린다.

## 3. 캠페인 켜기 / 끄기

- **켜기**: `application-prod.yml` 에 값 커밋(PR 리뷰·이력) 또는 ECS 태스크 정의 env(`kbap-infra`)로 주입 → 배포. 음식 id 는 prod DB 에서 READY 인지 먼저 확인한다(dev 대조표: `외국인음식/output/K-Food_TOP100_db-match.md`, prod 는 별도 확인 — dev 에서 비빔밥·김밥·김치찌개·된장찌개가 FAILED 였다).
- **끄기**: 아무것도 안 해도 `ends-at` 경과 시 자동 폴백. 조기 종료는 `ends-at` 을 앞당기거나 `food-ids` 를 비워 배포.
- 프로덕션 2대는 각자 같은 설정을 읽으므로 인스턴스 간 차이 없음(캐시 아님).

## 4. 머지 전 체크

- `OpenApiConfig` 에 `apiVersion21Doc` 빈 추가됐는지(없으면 `OpenApiSnapshotTest` 실패).
- 1.0 홈 응답 무변경 — `HomeControllerTest` 전부 통과.
- 지식 위키 `api-versioning-policy.md` 표에 홈 `2.1+` 행 추가, `INDEX.md` 갱신.
