# Quickstart: 홈 인기 음식 목록 캐시 (KB-725)

## 1. 테스트

```bash
./gradlew :api:test
```

- 단위: `PopularFoodIdCacheTest` — Spring 없이 가짜 Ticker·카운팅 로더. 집계 1회·TTL 재집계·동시 50 → 1회·실패 미캐시·빈 목록 캐시.
- 통합: `HomePopularCacheTest` — 캐시 히트에서 사용자별 안전 여부가 달라지는지, 삭제 음식이 빠지는지.
- 회귀: `HomeControllerTest`·`HomeGuestTest` 검증 본문 무수정 통과.

Kotest 는 Gradle `--tests` 필터를 무시하므로 모듈 전체로 돈다(메모리 기록).

## 2. 로컬 확인

```bash
set -a; source ../../../.env; set +a
DB_USERNAME=root DB_PASSWORD=root SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon
```

1. `GET /api/home?lang=en`(헤더 `X-API-Version: 1.0`) 를 두 번 호출한다.
2. SQL 로그에서 `food_view_log … group by` 집계가 **첫 호출에만** 찍히고 두 번째 호출엔 `food … where id in (…)` 만 찍히는지 본다.
3. 음식 상세를 몇 번 조회해 조회 로그를 쌓아도 홈 순서는 바뀌지 않는다(2일 TTL). 재기동하면 새 순서가 반영된다.

## 3. dev 부하 테스트 재실행 (SC-005, 사용자 수행)

1. develop 머지·dev 배포 후 VPN 을 끄고(`scutil --nc stop "Unicorn HTTPS 2"`) 홈 동시 1,000명·타임아웃 30초 시나리오를 다시 돌린다.
2. RDS 슬로우 로그에서 `findPopular` 집계 쿼리 호출 수가 인스턴스별 1회 수준인지, CloudWatch 에서 RDS CPU 가 100% 에 닿지 않는지, k6 성공률이 1차(30%) 대비 오르는지 확인한다.
3. 결과를 Notion 부하 테스트 페이지에 기록한다. 리뷰 인기 쿼리(`findMostReviewed`)는 이번 범위 밖이라 남은 DB 시간의 대부분을 차지할 수 있다 — 후속 태스크로 분리.
