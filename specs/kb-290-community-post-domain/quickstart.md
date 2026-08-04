# Quickstart: 커뮤니티 게시글 작성/수정/삭제 (KB-290)

## 자동 검증

```bash
./gradlew :common:test :api:test        # 엔티티 단위 + MockMvc 통합 + ArchUnit
./gradlew :api:test --tests "com.kbap.api.community.*"
```

통합 테스트는 MySQL Testcontainers 위에서 Flyway 마이그레이션을 실행하고 `ddl-auto=validate` 로 엔티티↔스키마 정합까지 검증한다.

## 수동 검증 (local 프로필)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

1. 로그인해 access token 확보 (`ACCESS`).
2. (사진 첨부 시) 기존 업로드 흐름으로 presigned URL 발급(purpose=`community`) → 업로드 → 완료 등록 → key 확보.
3. 글 작성:
   ```bash
   curl -X POST localhost:8080/api/v1/community/posts \
     -H "Authorization: Bearer $ACCESS" -H "Content-Type: application/json" \
     -d '{"content":"오늘 김치찌개 최고","imagePaths":[],"foodIds":[1]}'
   ```
   → `success=true`, `postId` 반환.
4. 제약 위반 확인: 본문 2,001자 → 400 검증 오류 / `foodIds` 4개 → 400 / 미등록 foodId → `COMMUNITY-004`.
5. 수정: `PUT /api/v1/community/posts/{postId}` 같은 body → `editedAt` 채워짐. 다른 회원 토큰으로 → 403 `COMMUNITY-002`.
6. 삭제: `DELETE /api/v1/community/posts/{postId}` → 이후 수정·재삭제 시 400 `COMMUNITY-001`. DB 에서 `status='DELETED'` row 보존 확인.
7. Swagger UI(`/swagger-ui.html`)에서 community 태그 3개 엔드포인트 문서 노출 확인.
