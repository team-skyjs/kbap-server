# Quickstart: 커뮤니티 댓글/대댓글 검증

## 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

액세스 토큰 2개(회원 A·B) 준비 — Swagger UI(`/swagger-ui.html`)의 auth 로그인 사용.

## 시나리오 (Swagger 또는 curl)

```bash
BASE=http://localhost:8080/api/v1

# 0) 글 하나 작성 (회원 A)
curl -s -X POST $BASE/community/posts -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"content":"김치찌개 최고"}'                        # → postId=P

# 1) 최상위 댓글 (회원 B)
curl -s -X POST $BASE/community/posts/P/comments -H "Authorization: Bearer $B" -H 'Content-Type: application/json' \
  -d '{"content":"정말 맛있죠"}'                          # → commentId=C1, parentCommentId=null

# 2) 답글 (회원 A → C1)
curl -s -X POST $BASE/community/posts/P/comments -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"content":"@B 인정","parentCommentId":C1}'         # → commentId=C2, parentCommentId=C1

# 3) 대댓글에 답글 → 1depth 정규화 확인
curl -s -X POST $BASE/community/posts/P/comments -H "Authorization: Bearer $B" -H 'Content-Type: application/json' \
  -d '{"content":"@A ㅋㅋ","parentCommentId":C2}'         # → parentCommentId=C1 (C2 아님!)

# 4) 목록 — 등록순, C1 아래 replies 2건
curl -s $BASE/community/posts/P/comments -H "Authorization: Bearer $B"

# 5) 타인 댓글 수정 시도 → 403 COMMUNITY-007
curl -s -X PUT $BASE/community/comments/C1 -H "Authorization: Bearer $A" -H 'Content-Type: application/json' \
  -d '{"content":"수정"}'

# 6) 통삭제 — C1 삭제(회원 B) 후 목록에서 C1·대댓글 전부 소실, 피드 commentCount=0
curl -s -X DELETE $BASE/community/comments/C1 -H "Authorization: Bearer $B"
curl -s $BASE/community/posts/P/comments -H "Authorization: Bearer $B"          # items: []
curl -s "$BASE/community/posts?lang=en" -H "Authorization: Bearer $A"           # commentCount: 0

# 7) 게스트 목록 → 401
curl -s $BASE/community/posts/P/comments
```

## 테스트

```bash
./gradlew :common:test --tests "com.kbap.common.domain.community.model.CommentTest"
./gradlew :api:test --tests "com.kbap.api.community.CommentControllerTest" \
                    --tests "com.kbap.api.community.CommentReadControllerTest" \
                    --tests "com.kbap.api.community.PostingReadControllerTest"
./gradlew build          # 전체(ArchUnit ModuleBoundaryTest 포함)
```

통합 테스트는 MySQL Testcontainers 로 돌며 Flyway 마이그레이션이 그대로 적용된다(엔티티↔스키마 `ddl-auto=validate` 정합 검증 포함).
