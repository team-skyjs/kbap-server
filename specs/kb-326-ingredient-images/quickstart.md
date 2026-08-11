# Quickstart: KB-326 온보딩 재료 이름·이미지 공개 조회

## 작업 위치

워크트리 `.claude/worktrees/kb-326-ingredient-images` (브랜치 `kb-326-ingredient-images`, develop 371ee4c8 분기). 모든 편집·빌드는 워크트리 루트 기준.

## 검증 명령

```bash
./gradlew :api:test --tests "com.kbap.api.ingredient.*"      # 이 기능 테스트만
./gradlew :api:test --tests "com.kbap.api.openapi.OpenApiSnapshotTest"  # 스냅샷 정합
./gradlew build                                               # 전체 (머지 전 필수)
```

통합 테스트는 MySQL Testcontainers — 로컬 Docker 데몬 필요. Flyway on(테스트 리소스) 이므로 신규 마이그레이션이 자동 적용돼 엔티티↔스키마 정합(`ddl-auto=validate`)이 함께 검증된다.

## 수동 확인 (선택)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
curl 'http://localhost:8080/api/ingredients?lang=ko'   # 토큰 없이 200 + 81건
curl 'http://localhost:8080/api/ingredients'            # 400
```

## 주의 함정

- 이 API 는 **공개**가 의도 — `WebConfig` 의 JWT `addUrlPatterns` 에 `/api/ingredients` 를 **추가하지 말 것**(추가하면 전 시나리오 401).
- 마이그레이션 파일명은 생성 시점 timestamp(`Vyyyy.MM.dd.HH.mm.ss__ingredient_image_path.sql`) — 정수 버전 금지.
- scan 테스트 손스텁 CREATE TABLE 이 food 계열 컬럼 추가 시 필요했던 것과 달리, ingredients 는 Flyway 전체 적용 경로라 별도 스텁 없음 — 단 전체 `./gradlew build` 로만 잡히는 교차 실패가 있을 수 있으니 머지 전 전체 빌드 필수.
