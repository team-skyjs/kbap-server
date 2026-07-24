# Quickstart: KB-167 E2E 시나리오 테스트

## 실행

```bash
# 전체 테스트(시나리오 포함)
./gradlew :app:api:test

# 시나리오만 선별
./gradlew :app:api:test -Dkotest.tags="scenario"

# 시나리오 제외
./gradlew :app:api:test -Dkotest.tags="!scenario"

# 단일 여정
./gradlew :app:api:test --tests "com.kbap.app.api.scenario.HappyPathScenarioTest"
```

전제: Docker 데몬 실행 중(Testcontainers — MySQL 8.4 + Redis 8).

## 반복 실행 안전(SC-004) 확인

```bash
./gradlew :app:api:test -Dkotest.tags="scenario" && \
./gradlew :app:api:test -Dkotest.tags="scenario" --rerun-tasks
```

여정마다 UUID 기반 신규 소셜 계정으로 가입하므로 테이블 청소 없이 반복 통과해야 한다.

## 구조 한눈에

- 시나리오: `app/api/src/test/kotlin/com/kbap/app/api/scenario/` — 여정당 1 `BehaviorSpec`, `@Tags("scenario")`.
- 본문은 `ScenarioApiDriver` 의 **한국어 스텝 메서드 조립**으로 읽는다: `회원가입한다()` → `온보딩한다()` → `스캔한다()` ….
- 외부 시스템은 전부 페이크(소셜 인증=idToken→sub 파생, vision=`FakeMenuBoardVisionExtractor.program`, S3=`FakePresignedUploadPortConfig`+`FakeStorageObjectStore`) — 네트워크 0.
- 음식 데이터는 `ScenarioFoodSeed` 가 insert-if-absent 로 준비 — Flyway 기피물질 카탈로그(81종)를 절대 DELETE 하지 않는다.
