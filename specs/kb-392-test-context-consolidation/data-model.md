# Data Model: KB-392 (개념 모델 — 영속 변경 없음)

## 컨텍스트 캐시 키 구성 요소

| 요소 | 현재 값들 | 통합 후 |
|------|-----------|---------|
| 부트 클래스 | api `KbapApiApplication` / common `*TestApp` 7 / batch `KbapBatchApplication` | api 동일 / common `CommonTestApp` / batch 동일 |
| `properties` | api: 없음·`hibernate.generate_statistics`·`logging…ecs` | 없음·`ecs`(1) |
| `@Import` 집합 | api 5조합 / common 1 / batch 2 | 모듈당 1(합성 애너테이션이 단일 출처) |
| MockMvc 자동구성 | api 有/無 / batch 有/無 | 항상 有 |

→ 컨텍스트: api 8→**2**, common 7→**1**, batch 3→**1**.

## 페이크 (api 테스트 전용, 모든 컨텍스트에 `@Primary`)

| 빈 | 타입 | 기본 동작 | 프로그래머블 |
|----|------|-----------|--------------|
| `FakeSocialTokenVerifier` | `SocialTokenVerifier` | `SocialIdentity(GOOGLE, idToken, "user@gmail.com")` | `failWith(ErrorCode)` / `reset()` |
| `FakeSocialAccountDeleter` | `SocialAccountDeleter` | 호출 기록 | `fail()` / `reset()` |
| `FakePlaceSearchClient` | `PlaceSearchClient` | 빈 결과 | `returns(...)` / `failure` / `reset()` |

## 한 DB 를 공유하는 클래스 수

api 기본 컨텍스트: 44 → 75. 격리 규율은 기존과 동일(유일 시드명·자체 정리).
