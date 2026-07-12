# Quickstart: 회원 랭킹 (KB-123)

## 개발 환경

이 기능은 워크트리 `~/source_code/meogo/meogo-server-kb-123`(브랜치 `kb-123-member-ranking`, base `origin/develop`)에서 개발한다. KB-124(프로필 수정 부분 수정 전환)는 **옆 워크트리에서 병렬 진행 중**이며, 두 작업 모두 `MemberProfileUseCase`·`MemberApi`/`MemberController`·`MemberControllerTest` 를 건드린다 — 먼저 머지된 쪽 기준으로 나머지를 리베이스한다.

## 테스트 실행

```bash
./gradlew :core:member:test --tests "com.meogo.core.member.MemberRankingTest"      # 도메인 단위(빠름)
./gradlew :application:client:test --tests "*MemberRanking*"                        # 유스케이스(페이크)
./gradlew :application:client:test --tests "*ScanUseCaseHistoryTest"                # 스캔 1회당 카운트업
./gradlew :infra:persistence:test --tests "*MemberRankingRepositoryAdapterTest"     # 카운터 upsert(Testcontainers)
./gradlew :app:api:test --tests "com.meogo.app.api.member.MemberControllerTest"     # MockMvc + Testcontainers
./gradlew test                                                                      # 전체
```

통합 테스트는 MySQL 8.4 Testcontainers 를 쓴다(Docker 필요).

## 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

1. 로그인해 access token 을 얻는다.
2. 메뉴판 스캔을 몇 번 수행한다(스캔 1회 = 메뉴판 1장 → `member_ranking.scan_count` 가 1씩 오른다).
3. 랭킹 상세:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/members/me/ranking
   ```
   → `score = 스캔 횟수 × 2`(리뷰 도메인 부재로 리뷰·다양성은 0), `tier`·`level`·`pointsToNext` 확인.
4. 프로필:
   ```bash
   curl -H "Authorization: Bearer $TOKEN" localhost:8080/api/v1/members/me/profile
   ```
   → `ranking` 요약이 3번 응답의 값과 일치하는지 확인(breakdown 은 없음).
5. 토큰 없이 3번을 호출하면 401.

Swagger: `localhost:8080/swagger-ui/index.html` — "회원" 태그에 랭킹 상세가 추가된다.

## 검증 케이스 (정책 문서 기준)

리뷰 8 + 고유 음식 6 + 스캔 9 → score 128 → `explorer`(level 3), nextTier `regular`, pointsToNext 52.
현재는 리뷰가 0이므로 이 케이스는 **도메인 단위 테스트에서만** 재현된다(리뷰 카운트를 직접 주입).

## 배포 시 유의

- Flyway 마이그레이션 1건이 추가됐다(`V2026.07.12.23.14.05__create_member_ranking_table.sql` — `member_ranking`). 기존 회원의 스캔 횟수는 소급되지 않는다(카운터는 배포 이후 스캔부터 쌓인다).

## 배포 후 할 일

- 프로필 응답에 `ranking` 요약이 실린다는 점을 FE(예진)에 공유한다 — 랭킹 정책 문서는 랭킹을 상세 엔드포인트에서만 받는 걸 전제로 쓰였다.
