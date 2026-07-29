# Quickstart: 리뷰 CRUD (KB-128)

## 검증 명령

```bash
./gradlew :common:test --tests "com.kbap.common.domain.review.*"   # PR1 — 도메인 단위·영속
./gradlew :api:test --tests "com.kbap.api.review.*"                # PR2·3 — MockMvc 통합
./gradlew :api:test --tests "com.kbap.api.food.*"                  # PR4 — 상세 확장 회귀
./gradlew :api:test --tests "com.kbap.api.architecture.*"          # 경계·ErrorCode 형식
./gradlew build                                                    # 머지 전 전체 (Testcontainers 필요 — Docker)
```

## 수동 확인 (local 프로필)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# Swagger UI 에서 Review 태그 확인 → 로그인 토큰으로:
# 1) POST /api/v1/image-uploads (purpose=REVIEW) → 업로드 → POST /api/v1/reviews
# 2) GET /api/v1/foods/{id}/reviews?countryCode=VN
# 3) GET /api/v1/foods/{id} 에서 averageRating·sameCountryAverageRating 확인
```

## PR 체크포인트

| PR | 머지 게이트 |
|----|-------------|
| 1 persistence | `MigrationValidationTest` 통과(Flyway+validate), `ModuleBoundaryTest` 맵 정확 일치, 영속 테스트 Green |
| 2 write | CRUD 401/403·이미지 소유·랭킹 카운트 시나리오(첫/추가/마지막) MockMvc Green |
| 3 lists | keyset 21건 경계·국적 필터·내 리뷰 MockMvc Green |
| 4 rating | null 규칙 3분기(비회원/국적 미보유/리뷰 0건)·반올림 MockMvc Green |

주의: `:api` 통합 테스트는 Flyway 실행 + `ddl-auto=validate` — 엔티티↔마이그레이션 불일치 시 컨텍스트 부팅 자체가 실패한다. 테스트 시드는 raw JDBC INSERT 선례(`FoodTestSeed`·`BookmarkControllerTest`)를 따라 review 시드 헬퍼를 만든다.
