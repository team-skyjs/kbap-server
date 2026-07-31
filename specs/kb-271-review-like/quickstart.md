# Quickstart: 리뷰 좋아요 (kb-271)

## 구현 순서 요약 (Test-First)

1. **마이그레이션 + 엔티티/리포지토리** (`:common` + `:api` 리소스)
   - `ReviewLikeJpaRepositoryTest`(Testcontainers) 먼저: upsert 신규/중복/부활, 유니크 제약, 배치 집계 2종 — Red 확인
   - `V<현재시각>__review_like_table.sql`(data-model.md 의 DDL) + `ReviewLike` + `ReviewLikeJpaRepository` 로 Green
2. **등록/취소 API** (`:api`)
   - `ReviewLikeControllerTest`(MockMvc) 먼저: 등록 200 / 중복 등록 200 / 취소 200 / 빈 취소 200 / 없는 리뷰 400 REVIEW-001 / 미인증 401 — Red 확인
   - `ReviewService.likeReview`·`unlikeReview` + `ReviewController` 매핑 + `ReviewApi` swagger 로 Green
3. **목록 응답 확장** (`:api`)
   - `ReviewListControllerTest` 에 likeCount·likedByMe 시나리오 추가 — Red 확인
   - `ReviewResponse` 필드 추가 + `ReviewService.toPage` 배치 enrich 로 Green

## 검증 명령

```bash
./gradlew :common:test --tests "com.kbap.common.domain.review.*"
./gradlew :api:test --tests "com.kbap.api.review.*"
./gradlew build            # 전체 회귀 (ArchUnit 포함)
```

## 수동 확인 (local 프로필)

```bash
./gradlew :api:bootRun     # SPRING_PROFILES_ACTIVE=local

# 좋아요 등록 → 멱등 재등록 → 목록에서 likeCount/likedByMe 확인 → 취소
curl -X POST   -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/reviews/1/like
curl -X POST   -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/reviews/1/like   # 그대로 성공
curl           -H "Authorization: Bearer $TOKEN" "localhost:8080/api/v1/reviews?foodId=1"
curl -X DELETE -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/reviews/1/like
```

## 주의 지점

- **마이그레이션 파일명은 생성 시점 로컬 시각**으로 명명(점 구분 timestamp) — 정수 버전 금지.
- 보호 경로: `WebConfig` 의 인증 필터 패턴 `${ApiPaths.V1}/reviews/*` 가 서블릿 path-mapping 이라 `/reviews/{id}/like` 다중 세그먼트까지 이미 커버 — 필터 등록 변경 불필요(확인 완료). 단 미인증 401 테스트로 회귀 고정할 것.
- 집계 쿼리에 `status` 조건을 직접 달지 않는다 — `@SQLRestriction` 이 자동 적용.
- 동시성 스레드 테스트는 작성하지 않는다(비치명 경합, 유니크 제약으로 충분 — 2026-07-30 규약).
- E2E(kb-167)·기존 리뷰 테스트는 additive 필드라 깨지지 않아야 정상 — 깨지면 오버스펙 assertion 여부를 먼저 의심.
