# Quickstart: kb-321 전체 리뷰 피드 + 음식 정보 보강

## 작업 위치

워크트리 `.claude/worktrees/kb-321-review-feed-food-info/` (브랜치 `kb-321-review-feed-food-info`, develop 기준). **모든 편집·빌드는 워크트리 경로에서.**

## 검증 명령

```bash
# repository 쿼리 테스트 (:common — Testcontainers MySQL)
./gradlew :common:test --tests "com.kbap.common.domain.review.ReviewJpaRepositoryTest"

# API 통합 테스트 (:api — MockMvc)
./gradlew :api:test --tests "com.kbap.api.review.*"

# 전체 (ArchUnit 포함)
./gradlew build
```

## 수동 확인 (선택)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# Swagger UI 에서 GET /api/v1/reviews/feed?lang=en 호출 (Authorization 필요)
```

## 핵심 참조 파일

- `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` — `toPage` 배치 조회 패턴(작성자·좋아요)에 음식 합류
- `common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt` — 기존 커서 쿼리 선례
- `api/src/main/kotlin/com/kbap/api/food/FoodController.kt` — `lang` 필수 + `LanguageCode.from` 선례(KB-201)
