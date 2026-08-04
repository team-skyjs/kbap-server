# Quickstart: 커뮤니티 피드 조회 + 글 상세 (KB-291)

## 검증 명령

```bash
./gradlew :api:test --tests "com.kbap.api.community.*"   # 기능 테스트
./gradlew :api:test -Dkotest.tags="arch"                  # 경계 검사
./gradlew build                                           # 전체
```

## 수동 확인 (local 프로필)

```bash
./gradlew :api:bootRun   # SPRING_PROFILES_ACTIVE=local

# 1. 게스트 첫 페이지
curl "localhost:8080/api/v1/community/posts?lang=en"

# 2. 게스트 2페이지 (1페이지 응답의 nextCursor 사용)
curl "localhost:8080/api/v1/community/posts?lang=en&cursor=<nextCursor>"

# 3. 게스트 3페이지 → 401 COMMUNITY-005
curl "localhost:8080/api/v1/community/posts?lang=en&cursor=<2페이지 nextCursor>"

# 4. 회원 3페이지 → 200
curl -H "Authorization: Bearer <access>" "localhost:8080/api/v1/community/posts?lang=en&cursor=<2페이지 nextCursor>"

# 5. 상세 (게스트 가능)
curl "localhost:8080/api/v1/community/posts/1?lang=en"
```

## 구현 파일 지도

| 위치 | 변경 |
|------|------|
| `common/.../domain/community/PostingJpaRepository.kt` | `findPage` + `findIdsFrom`(게이트용) 추가 |
| `common/.../core/error/ErrorCode.kt` | `COMMUNITY_LOGIN_REQUIRED`(COMMUNITY-005, 401) 추가 |
| `api/.../community/CommunityService.kt` | `getPostingPage`·`getPosting` + 단일 조립 함수 추가 |
| `api/.../community/CommunityPostingItemResponse.kt` | 신규 — author·foodTags·counts 포함 응답 |
| `api/.../community/CommunityPostingListRequest.kt` | 신규 — lang 필수·cursor 선택 |
| `api/.../community/CommunityController.kt`·`CommunityApi.kt` | GET 2개 추가(`@AuthMemberIdOrNull`) |
| `api/.../core/auth/JwtAuthenticationFilter.kt` | GET 게스트 예외(`shouldNotFilter`) 추가 |
| `api/.../core/config/WebConfig.kt` | 예외 패턴 주입 |
| `api/src/test/.../community/CommunityPostingReadControllerTest.kt` | 신규 — 피드·게이트·상세·익명화 통합 테스트 |

Flyway 마이그레이션 없음.
