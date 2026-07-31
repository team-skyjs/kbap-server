# Research: 사용자 차단 (Member Block)

Technical Context 에 NEEDS CLARIFICATION 은 없었고, 설계 분기점 7건을 코드 사실 기반으로 결정했다.

## R0. 컨텍스트 소속 — 독립 `block` 컨텍스트 (member 소속 기각)

- **Decision**: `MemberBlock` 엔티티·리포지토리·`MemberBlockService` 를 신규 컨텍스트 `common.domain.block` 에 두고, `ModuleBoundaryTest` 의 `allowedDomainDeps` 에 `"block" to setOf("member")` 단방향 1건을 추가한다(대상 회원 존재 검증이 `MemberService.getMemberOrNull` 을 쓰기 때문 — member 는 block 을 모르므로 순환 없음). api 기능 패키지도 `com.kbap.api.block` 으로 대응시킨다. 엔티티명은 `Block` 이 아니라 `MemberBlock` 을 유지한다(단독 `Block` 은 과도하게 범용적, 테이블 `member_block` 과 대응 유지).
- **Rationale**: 차단은 UGC 공통 노출 제외 규칙(FR-011)로 소비자가 리뷰·향후 커뮤니티 쪽이다 — member 컨텍스트(신원·프로필·랭킹)와 관심사가 다르고, member 패키지는 이미 모델 13개·서비스 15메서드로 크다. 분리 비용은 허용 맵 한 줄(맵 수정이 공인된 확장 경로)이고 파일 수는 동일하다. 에러 코드도 `BLOCK-` 접두를 신설해 member 채번과 얽히지 않는다(R3).
- **Alternatives considered**: member 컨텍스트 소속(`common.domain.member`) — Jira 원문 표기이자 "회원 간 관계" 라는 점에선 자연스럽지만, 위 이유(소비 방향·비대화)로 기각. (2026-08-01 사용자 결정)

## R1. 소프트삭제 부활 방식 — 상태 무시 native 조회 + dirty checking

- **Decision**: `MemberBlockJpaRepository` 에 native query 로 상태를 무시하고 (blocker, blocked) 쌍 행을 찾는 메서드를 두고, `MemberBlockService.block()` 이 (1) 행 있음+DELETED → `active()`(dirty checking), (2) 행 있음+ACTIVE → no-op, (3) 행 없음 → `save()` 로 처리한다. 경합으로 insert 가 UNIQUE 위반하면 `DataIntegrityViolationException` 을 잡아 멱등 성공으로 마감한다.
- **Rationale**: `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 이 JPQL·파생 쿼리에 상시 적용되므로 DELETED 행은 일반 조회에 안 잡힌다 — native 만 이 필터를 우회한다(기존 선례: `FoodJpaRepository`·`ScanHistoryJpaRepository` 의 `nativeQuery = true`). `BaseEntity.active()` 가 이미 존재해 부활에 새 코드가 필요 없다. 동시성은 컨벤션(2026-07-30)대로 unique 제약 + 예외 폴백 최소 방어만 한다 — 차단 경합은 치명 경로가 아니다.
- **Alternatives considered**: ① 원자 UPDATE(`@Modifying` native)로 부활+멱등을 한 번에 — 쿼리 2종(UPDATE+INSERT)·`clearAutomatically` 관리가 필요해 조각이 더 많다. ② upsert(`INSERT ... ON DUPLICATE KEY UPDATE`) — JPA 엔티티 생명주기 밖이라 감사 컬럼·영속성 컨텍스트 정합이 나빠진다.

## R2. 빈 제외 목록의 `NOT IN` 처리 — 센티널 `-1`

- **Decision**: `findFoodReviewPage` JPQL 에 `and r.memberId not in :excludedMemberIds` 를 추가하고, 호출부(`ReviewService`)가 빈 목록일 때 `listOf(-1L)` 센티널을 넘긴다(id 는 IDENTITY ≥ 1 이라 실회원과 충돌 불가).
- **Rationale**: 빈 컬렉션의 `NOT IN ()` 는 Hibernate 버전·방언에 따라 렌더링이 갈리는 함정 지대다. 센티널은 쿼리 1개·분기 0개로 전 케이스가 동일 경로를 타 테스트가 단순해진다. "코드로 드러나지 않는 제약"이므로 짧은 라인 주석 허용 대상.
- **Alternatives considered**: ① Hibernate 6 의 빈 목록 자동 처리 의존 — 버전 업그레이드 시 조용히 깨질 수 있는 암묵 계약. ② `:flag = false or ...` 가드 파라미터 — 파라미터 2개·가독성 저하. ③ 빈/비빈 분기용 쿼리 2개 — JPQL 중복.

## R3. 차단 대상 검증 에러 코드 — `BLOCK-` 접두 신설 2건

- **Decision**: `SELF_BLOCK_FORBIDDEN("BLOCK-001", 400)`, `BLOCK_TARGET_NOT_FOUND("BLOCK-002", 404)` 를 추가한다. 대상 존재 검증은 `MemberService.getMemberOrNull`(active 만 노출하는 계약)로 하고 null 이면 `BLOCK_TARGET_NOT_FOUND` 를 던진다.
- **Rationale**: 도메인 접두 채번 체계에서 block 이 독립 컨텍스트가 됐으므로(R0) 접두도 `BLOCK-` 으로 신설한다(REVIEW-·SCAN- 신설과 동일 선례). Jira 가 "존재하지 않거나 탈퇴한 회원이면 404" 를 명시했는데 기존 `MEMBER_NOT_FOUND(MEMBER-003)` 는 400 으로 채번돼 있어 재사용하면 계약이 어긋난다(코드의 status 를 바꾸면 기존 API 전부에 영향). `ErrorCodeStatusTest` 가 형식·유일성을 자동 검증한다.
- **Alternatives considered**: ① `MEMBER_NOT_FOUND` 재사용(400) — 클라이언트 분기 코드로는 성립하지만 Jira 의 404 계약 위반. ② `MEMBER-010/011` 채번 — R0 이전(member 소속) 초안이었으나 컨텍스트 분리에 맞춰 폐기.

## R4. `/api/v1/reviews/me`(내 리뷰 목록)에는 차단 필터를 적용하지 않는다

- **Decision**: 차단 제외 필터는 음식 리뷰 목록(`GET /api/v1/reviews`)에만 적용한다. `findMemberReviewPage` 는 무변경.
- **Rationale**: 내 리뷰 목록은 조회자 본인의 글만 반환하고, 자기 자신 차단은 도메인 규칙으로 금지되므로 필터 대상이 **구조적으로 공집합**이다 — 적용하면 죽은 코드가 된다. spec 의 Edge Case 절도 "실질 영향 없음"을 인정한다. FR-009 의 "다른 회원의 리뷰가 노출되는 모든 지점"은 현재 음식 리뷰 목록 하나다(음식 상세는 집계만 노출 — R5).
- **Alternatives considered**: 두 쿼리 모두에 파라미터 추가(Jira 원문 표기) — 검증 불가능한 코드 경로가 생겨 기각.

## R5. 음식 상세의 리뷰 섹션은 무변경 — 집계는 전역 값

- **Decision**: `FoodController` → `ReviewService.getFoodRatingSummary`(평균 별점·리뷰 수·같은 국적 집계) 경로는 손대지 않는다.
- **Rationale**: 음식 상세는 리뷰 **목록** 이 아니라 **집계** 만 노출하며, FR-010/Jira 가 집계는 차단과 무관한 전역 값으로 못박았다(조회자별 평점 분기는 캐시·일관성만 해친다). 차단 전후 집계 불변은 MockMvc 테스트로 고정한다.
- **Alternatives considered**: 없음(요구사항이 명시적).

## R6. 차단 API 배치와 조회자 식별

- **Decision**: 컨트롤러·swagger 인터페이스·요청/응답 DTO 는 신규 기능 패키지 `com.kbap.api.block` 에 평탄하게 둔다(R0 의 도메인 컨텍스트와 대응). 차단 목록 응답 조립(차단 id → `MemberJpaRepository.findAllById` → 닉네임·`ImageUrls.resolve` 프로필 이미지)은 컨트롤러가 직접 한다. `GET /api/v1/reviews` 는 `@AuthMemberId` 파라미터를 추가해 조회자 id 를 받는다(swagger 인터페이스는 타입만 동기화).
- **Rationale**: 경로 prefix 는 `/api/v1/members/me/blocks` 지만 기능 패키지는 경로가 아니라 기능 단위로 가른다(선례: `home`·`bookmark`). `JwtAuthenticationFilter` 의 기존 패턴 `/api/v1/members/*` 가 하위 경로를 이미 보호한다(신규 보호 경로 등록 불필요 — 리뷰 도메인 함정 메모리의 "필터 등록" 항목 확인 완료). 목록 조립은 리포지토리 2회 호출 + map 이라 별도 창구 서비스를 만들면 위임 전용 계층(헌법 IV 금지)이 된다 — `FoodController` 가 컨트롤러 조립 선례. `GET /reviews` 는 필터로 이미 인증 강제되고 있었고(`WebConfig` 등록 확인) 컨트롤러가 id 를 안 받고 있었을 뿐이라, 파라미터 추가는 클라이언트 계약 변화가 없다. 탈퇴 회원은 `findAllById` 의 `@SQLRestriction` + member 파생 계약으로 자연 제외된다(spec Edge Case 일치). `ReviewAuthorResponse` 에는 프로필 이미지가 없으므로 차단 목록 전용 `BlockedMemberResponse` 를 새로 둔다.
- **Alternatives considered**: ① api 측 `MemberBlockFacade` 서비스 — 조립 3줄에 계층 1개는 과잉. ② `MemberService` 에 차단 메서드 추가 — 이미 public 메서드 15개로 비대하고, 차단은 독립 하위 능력이라 같은 패키지의 `MemberBlockService` 분리가 응집도·테스트 격리에 낫다(컨텍스트 동일 — 경계 영향 없음).
