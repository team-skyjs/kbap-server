# Quickstart: 모듈 다이어트 검증 (kb-244)

각 Stage 커밋 전 필수 게이트 — 전부 통과해야 다음 Stage 로 간다.

## 전체 빌드 + 테스트 (P1 게이트)

```bash
./gradlew clean build
```

## 모듈 구성 확인 (SC-001)

```bash
./gradlew projects
# 기대(Stage C 이후): :common, :app:api, :app:batch, :infra:{llm,auth,redis,storage} — 7개
```

## 의존 방향 확인 (FR-004, US2 게이트)

```bash
./gradlew :app:api:dependencies --configuration runtimeClasspath | grep "project :"
./gradlew :app:batch:dependencies --configuration runtimeClasspath | grep "project :"
./gradlew :infra:auth:dependencies --configuration compileClasspath | grep "project :"
# 기대: api→common(+infra), batch→common(+infra:llm·storage), infra→common. api↔batch 상호 참조 없음
```

## 두 앱 기동 (P1 게이트)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun    # 기동 확인 후 종료
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun  # 잡 파라미터 없이 기동만 확인
```

## 경계 규칙 (P3 게이트)

```bash
./gradlew :app:api:test --tests "com.kbap.app.api.architecture.ModuleBoundaryTest"
```

## 이동 무결성 스팟체크

```bash
git log --follow --oneline -3 common/src/main/kotlin/com/kbap/domain/food/FoodService.kt  # 이력 보존(git mv) 확인
grep -rn "project(\":core\")\|project(\":domain:\|project(\":application\")" --include="build.gradle.kts" .  # 잔존 참조 0건
```
