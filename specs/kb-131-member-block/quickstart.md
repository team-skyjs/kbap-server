# Quickstart: 사용자 차단 (Member Block)

## 검증 명령

```bash
./gradlew :common:test --tests "com.kbap.common.domain.block.*"    # 도메인 서비스 단위 + 부활 시나리오(Testcontainers)
./gradlew :api:test --tests "com.kbap.api.block.*" --tests "com.kbap.api.review.*"    # MockMvc 차단 3종 + 리뷰 필터
./gradlew build                                                    # 전체(ArchUnit ModuleBoundaryTest 포함) — 최종 게이트
```

Docker 필수(Testcontainers MySQL). ArchUnit 만 빼려면 `-Dkotest.tags="!arch"`.

## 수동 확인 (local 프로필)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# Swagger UI 에서 인증 후:
# 1) POST /api/v1/members/me/blocks {"memberId": <리뷰 작성자 id>} → 200
# 2) GET /api/v1/reviews?foodId=<그 음식> → 해당 작성자 리뷰 미노출
# 3) GET /api/v1/foods/<foodId> → 평균 별점·리뷰 수 차단 전과 동일
# 4) GET /api/v1/members/me/blocks → 차단 목록(닉네임·프로필 이미지)
# 5) DELETE /api/v1/members/me/blocks/<memberId> → 200, 리뷰 재노출
# 6) 1) 재실행(재차단) → 200, 다시 미노출 (UNIQUE 위반 없음 = 부활 경로)
```

## 구현 시 주의 (선행 확인된 함정)

- **마이그레이션 파일명**은 구현 시점 로컬 시각으로 채번한다(`V2026.MM.dd.HH.mm.ss__member_block_table.sql`) — plan 의 파일명은 자리표시자.
- `GET /api/v1/reviews` 컨트롤러에 `@AuthMemberId` 추가 시 **`ReviewApi` 인터페이스 파라미터도 타입만** 맞춘다(애너테이션 중복 선언 금지).
- 빈 차단 목록은 `NOT IN` 센티널 `listOf(-1L)` 로 — 짧은 라인 주석으로 사유 명시(research R2).
- 부활 조회는 native query 여야 한다 — 파생 쿼리·JPQL 은 `@SQLRestriction` 때문에 DELETED 행을 못 본다(research R1).
- `WebConfig` 수정 불필요 — `/api/v1/members/*` 패턴이 `/members/me/blocks` 를 이미 보호(확인 완료).
- `common.domain.block` 패키지를 만드는 순간 `ModuleBoundaryTest` 의 foundContexts 일치 검사가 깨진다 — **`allowedDomainDeps` 에 `"block" to setOf("member")` 추가를 같은 task 에서** 처리한다(arch 태그 테스트라 전체 build 에서만 잡힘).
