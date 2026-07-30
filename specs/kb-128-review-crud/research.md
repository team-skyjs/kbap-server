# Research: 리뷰 CRUD (KB-128)

Phase 0 — 스펙에 NEEDS CLARIFICATION 없음(Jira 기획 확정). 아래는 코드베이스 선례 조사와 설계 결정 기록이다.

## R1. 랭킹 카운트 — 컬럼 기존재, JPQL 원자 증감

- **Decision**: `member.review_count`·`unique_reviewed_food_count` 는 init_schema 에 이미 존재(마이그레이션 불필요). 증감은 `MemberJpaRepository` 에 `@Modifying` JPQL 로 추가 — `increaseScanCount` 선례(`Member.kt`의 `Ranking.of(scanCount, reviewCount, uniqueReviewedFoodCount)` 가 이미 소비 중). 증가·감소 각 1개 메서드로 두 컬럼을 한 문장에 갱신: `set m.reviewCount = m.reviewCount + 1, m.uniqueReviewedFoodCount = m.uniqueReviewedFoodCount + :uniqueDelta`(uniqueDelta ∈ {0,1}). 감소 쿼리는 `and m.reviewCount > 0` 가드. 공개 창구는 `MemberService`(도메인 서비스 — 반환 0건이면 `MEMBER_NOT_FOUND`).
- **Rationale**: 읽고-더해-쓰기 금지(헌법·이슈 명시), 스캔 카운트와 동일 패턴. 두 컬럼 한 UPDATE 로 부분 갱신 상태 제거.
- **Alternatives**: 관리 엔티티 dirty checking — 동시 요청 시 lost update, 기각. 이벤트 비동기 반영 — 과설계, 기각.
- **첫/마지막 리뷰 판정**: 같은 트랜잭션 안에서 `countByMemberIdAndFoodId` (작성: 저장 후 count==1 → uniqueDelta=1 / 삭제: 삭제 후 count==0 → uniqueDelta=1). 같은 회원 동시 중복 작성/삭제의 고유 음식 수 오차는 **감수한다**(2026-07-30 최종 — 비즈니스 영향 미미, 격리수준 조정·과한 동시성 방어 금지는 CLAUDE.md 컨벤션으로 명문화). 작성 시 카운트 UPDATE 는 INSERT 보다 먼저 실행한다 — INSERT 의 FK 검증(fk_food_review_member)이 member 행 S-lock 을 선점한 뒤 UPDATE 가 X-lock 을 요구하면 동시 작성 간 데드락 500 이 난다(실측). 증감은 컬럼별 단일 메서드 4개(review_count·unique 각 증/감, 감소는 하한 가드). 수정/삭제 대상 리뷰 행의 X-lock 조회(findByIdForUpdate)는 유지 — 동시 삭제 중복 차감·수정의 삭제 되살림 같은 상태 훼손 방지는 격리수준과 무관한 행 락 한 줄이다.

## R2. 국적 스냅샷 — 작성 시점 `author_country_code` VARCHAR

- **Decision**: 작성 시 `MemberService` 조회로 `member.profile.countryCode`(JSON 프로필 내 `CountryCode?` enum)를 읽어 `review.author_country_code`(VARCHAR, NULL 허용)에 코드 문자열로 스냅샷. 이후 평점·필터는 이 컬럼만 사용(member 조인 없음). 국적 미보유 회원의 리뷰는 NULL — 어떤 국적 집계·필터에도 안 잡힘(전체 평점에는 포함).
- **Rationale**: 이슈 확정 사항 — 회원 국적 변경에도 과거 리뷰 불변, 조인 제거.
- **Alternatives**: member 조인 실시간 계산 — 불변 요구 위반, 기각. `CountryCode` enum 컬럼(`@Enumerated(STRING)`) — 국가 추가/삭제 시 과거 데이터가 enum 밖 값이 될 위험, 문자열 스냅샷이 더 안전. 필터 파라미터 검증에만 `CountryCode.from` 사용.
- **수정 시 스냅샷은 갱신하지 않는다** — "작성 시점" 정의 유지.

## R3. 사진 — JSON 컬럼 + 일괄 소유 검증

- **Decision**: `review.image_refs` JSON NULL 컬럼(`@JdbcTypeCode(SqlTypes.JSON) List<String>?`) — Food `avoidance_substances` 선례. 별도 테이블 없음(사진 생명주기 = 리뷰). 작성/수정 시 `UploadedImageRepository.findByPathIn`(신규 파생 쿼리)으로 일괄 조회 → 전 건 `isOwnedBy(memberId)` 확인, 하나라도 실패면 거부. `UploadPurpose.REVIEW`·presigned 발급은 기 구현이라 손대지 않는다.
- **Rationale**: 기존 `verifyImageAccess` 는 단건(`findByPath`) — 3장이면 쿼리 3번. `findByPathIn` 1번으로 충분. 스캔의 소유 검증은 TODO 주석 상태라 리뷰가 첫 실사용처.
- **Alternatives**: review_image 자식 테이블 — 조회·정합 비용만 늘고 이슈가 명시 기각. 단건 검증 3회 호출 — 동작은 같으나 불필요한 왕복.

## R4. 목록 — keyset 커서 (확립된 선례 그대로)

- **Decision**: `cursor: String?` → `CursorParser.parse`(음수/비숫자 `FOOD-002 INVALID_CURSOR` 재사용) → `(:cursor is null or r.id < :cursor) order by r.id desc` + `PageRequest.of(0, PAGE_SIZE+1)` +1건 트릭 → `Page<T>(items, hasNext, nextCursor)` 응답 래퍼. PAGE_SIZE=20. 음식별 목록은 `countryCode` 옵션 파라미터(`:countryCode is null or r.authorCountryCode = :countryCode`).
- **Rationale**: bookmark(`BookmarkJpaRepository.findPage`·`BookmarkService.getBookmarkPage`) 선례와 완전 동일 — 새 패턴 도입 없음.

## R5. 평점 집계 — 조회 시 AVG, 반올림은 응답 조립

- **Decision**: `ReviewJpaRepository` 에 JPQL 집계 — 전체 `select avg(r.rating), count(r) ... where r.foodId=:foodId`, 같은 국적 `... and r.authorCountryCode=:countryCode`. `@SQLRestriction("status='ACTIVE'")` 이 소프트삭제 제외를 자동 보장. 소수 첫째 자리 반올림은 api 응답 조립에서(`ReviewService`/Response). 리뷰 0건이면 avg NULL → 응답 null.
- **Rationale**: 이슈 확정 — 비정규화 컬럼 없음. 인덱스 `(food_id, ...)` 로 커버.
- **음식 상세 합성**: `FoodController.detail` 이 이미 `bookmarkService` 를 컨트롤러에서 합성하는 선례 — `ReviewService.getFoodRatingSummary(foodId, viewerCountryCode?)` 를 같은 방식으로 합성. viewer 국적은 `@AuthMemberIdOrNull` memberId 로 `MemberService.getMemberOrNull` 조회(비회원·국적 미보유 → sameCountry null).

## R6. ErrorCode — `REVIEW-` 접두 신설

- **Decision**: `REVIEW-001 REVIEW_NOT_FOUND`(400 — MEMBER-003·FOOD-001 의 not-found=400 관례), `REVIEW-002 REVIEW_FORBIDDEN`(403 — AUTH-008 선례), `REVIEW-003 REVIEW_IMAGE_NOT_VERIFIED`(400). 별점 범위·본문 길이·사진 수는 요청 DTO Bean Validation(400, COMMON 계열 핸들러 경로).
- **Rationale**: 접두별 001 채번 규칙·`ErrorCodeStatusTest` 형식 강제. 도메인별 예외 클래스 금지 — `BusinessException(errorCode)` 하나.

## R7. 경계 — ModuleBoundaryTest 허용 맵

- **Decision**: `allowedDomainDeps` 에 `"review" to emptySet()` 추가(`foundContexts shouldBe keys` 가 정확 일치 요구라 필수). review 도메인은 food·member 를 **타입으로 참조하지 않는다** — id 값·국적 코드 문자열만. 회원 조회·음식 존재 확인·이미지 검증·랭킹 증감은 전부 `com.kbap.api.review.ReviewService`(api 조합 계층)가 수행.
- **Rationale**: 도메인 간 의존 0 이 가장 단순하고, 합성은 api 기능 패키지 소관(헌법 II·ADR-0017).

## R8. 인증·검증 경계

- **Decision**: deny-by-default 화이트리스트(KB-132)라 신규 `/api/v1/reviews`·`/api/v1/foods/{id}/reviews`·`/api/v1/members/me/reviews` 는 자동 보호 — 화이트리스트 수정 없음. 컨트롤러 `@AuthMemberId`(음식 상세만 기존 `@AuthMemberIdOrNull` 유지). 요청 검증(rating 1~5·content ≤1000·images ≤3·foodId 필수)은 요청 DTO 의 Bean Validation 소유(헌법 V 검증 경계 조항), 엔티티는 `init`/도메인 메서드에서 동일 불변을 이중 방어(영속 경계).
- **테이블명**: `food_review` — 단수 관례(`bookmark`·`scan_history`) + 앱 리뷰 등과의 혼동을 피하는 도메인 접두(2026-07-30 개명). 이슈 본문의 "reviews" 는 복수형이지만 스키마 관례 우선.
- **이미지 용도 검증(2026-07-30 Codex 리뷰 반영)**: 소유 확인에 더해 경로의 용도 세그먼트(`images/review/`)까지 검증 — 본인 소유 PROFILE/SCAN 경로를 리뷰 사진으로 재사용하는 우회 차단(`REVIEW-003`). 용도는 presigned 키 구조에만 있고 엔티티에 컬럼이 없어 경로 세그먼트가 유일한 판별자.
