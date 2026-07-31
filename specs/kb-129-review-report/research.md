# Research: 리뷰 신고 (kb-129)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 구현 방식이 갈리는 지점의 결정 기록이다.

## R1. 신고 엔드포인트·저장 모델 일반화

- **Decision**: `POST /api/v1/reports` 단일 창구(body: targetType·targetId·reason·detail) + 단일 `reports` 테이블(target_type + target_id, UNIQUE(reporter_member_id, target_type, target_id)).
- **Rationale**: 신고 대상이 커뮤니티 게시글로 확장될 예정(clarify 세션 결정). 대상 추가가 enum 값 + 유스케이스 분기 추가로 끝나고 엔드포인트·테이블·FE 플로우가 불변.
- **Alternatives considered**: `POST /reviews/{reviewId}/reports`(Jira 원안 — 대상마다 경로·테이블 신설 필요, 기각), `POST /reports/reviews/{reviewId}`(경로 타입 분리 — 대상마다 엔드포인트 증가, 기각).

## R2. 중복 신고 방어 — UNIQUE 제약 + 위반 변환

- **Decision**: `reports` 에 UNIQUE(reporter_member_id, target_type, target_id). 유스케이스는 `existsBy` 선조회로 친절한 409 를 주고, 동시 요청 경합은 save 시 `DataIntegrityViolationException` 을 잡아 같은 409(`REPORT_DUPLICATED`)로 변환한다.
- **Rationale**: 프로젝트 동시성 규칙(2026-07-30 고정) — 치명 정합만 최소 수단(unique 제약)으로 막고 격리수준은 손대지 않는다. 신고 취소가 없어 소프트 삭제 행이 UNIQUE 와 충돌할 일도 없다(Jira 분석 그대로). IDENTITY 전략이라 `save()` 가 즉시 INSERT 를 치므로 위반은 save 호출 지점에서 잡힌다.
- **Alternatives considered**: 선조회만(경합 시 500 유출 — 기각), `INSERT IGNORE` 네이티브(성공/중복 구분 모호 — 기각), 격리수준 조정(프로젝트 금지 — 기각).

## R3. 목록 제외 필터 — 제외 목록이 비면 기존 쿼리 그대로

- **Decision**: `ReviewService.getFoodReviewPage` 가 호출자 회원 id 를 받아 `ReportJpaRepository` 에서 신고한 REVIEW 대상 id 목록을 조회하고, **비어 있으면 기존 쿼리를 그대로 호출**, 있으면 `and r.id not in :excludedIds` 를 더한 오버로드 쿼리를 호출한다. 조회 쿼리는 제외 id 목록만 알고 "신고"라는 개념을 모른다.
- **Rationale**: JPQL `not in` 에 빈 컬렉션을 넘기면 SQL 이 깨진다. 센티널 값(-1) 주입보다 서비스 분기가 정직하고, Jira 설계 지침("제외 목록이 비면 조건을 생략한다")과 일치. 향후 유저 차단은 제외 id 목록 합집합으로 얹으면 된다.
- **Alternatives considered**: 센티널 `listOf(-1)` 단일 쿼리(마법값 — 기각), Specification/QueryDSL 동적 쿼리(신규 의존·과설계 — 기각).
- 제외 대상 조회는 (reporter_member_id, target_type) 프리픽스 — UNIQUE 인덱스가 커버하므로 추가 인덱스 불필요.

## R4. 신고 유스케이스 배치 — 도메인 서비스 없이 api 기능 패키지

- **Decision**: 검증(리뷰 존재·자기 리뷰 거절)과 저장 조합은 `com.kbap.api.report.ReportService` 가 소유한다. `common.domain.report` 에는 엔티티·enum·리포지토리만 둔다.
- **Rationale**: 신고 로직의 소비자는 api 뿐(배치·인프라 무관)이고, 검증이 review 컨텍스트 조회를 필요로 하는 **요청 조합**이다 — ADR-0017 의 api 기능 패키지 소관. report 도메인 자체는 review 를 몰라야 컨텍스트 간 의존이 생기지 않는다(`ModuleBoundaryTest` 허용 맵 `"report" to emptySet()`).
- **Alternatives considered**: `common.domain.report.ReportService` 도메인 서비스(web·batch 공유 로직이 없어 위임 창구화 — KB-220 취지 위반, 기각), review 도메인에 신고 포함(게시글 신고 확장 시 소속이 깨짐 — clarify 결정과 상충, 기각).

## R5. 에러 코드 — REPORT 접두 신설

- **Decision**: `REPORT_SELF_TARGET("REPORT-001", 400)` · `REPORT_DUPLICATED("REPORT-002", 409)` · `REPORT_TARGET_NOT_FOUND("REPORT-003", 404)` 를 `ErrorCode` 에 추가한다.
- **Rationale**: 도메인 접두 + 3자리 채번 규약. 기존 `REVIEW_NOT_FOUND` 는 400 이라 spec 의 "대상 없음(404)" 의미와 다르고, 신고는 대상 타입이 늘어날 리소스라 자체 접두가 맞다. 형식·유일성은 `ErrorCodeStatusTest` 가 검증.
- **Alternatives considered**: `REVIEW_NOT_FOUND` 재사용(상태코드 400 불일치·게시글 확장 시 부적합 — 기각).

## R6. 인증 — 필터 include 목록 등록 + @AuthMemberId

- **Decision**: `WebConfig` 의 `JwtAuthenticationFilter` 등록에 `${ApiPaths.V1}/reports` 를 추가하고, 신고 컨트롤러와 음식 리뷰 목록 컨트롤러(`listFoodReviews`)에 `@AuthMemberId` 를 단다. `ReviewApi` 인터페이스 시그니처도 동기화한다(파라미터 애너테이션 위치 규약 — Spring 애너테이션은 구현 클래스에만).
- **Rationale**: 이 프로젝트의 인증 필터는 **명시 include 목록**이다(deny-by-default 아님) — 경로를 등록하지 않으면 신규 API 가 무인증으로 뚫린다(리뷰 도메인 선례에서 확인된 함정). `listFoodReviews` 는 현재 회원 id 를 받지 않으므로 제외 필터를 위해 추가한다(경로는 이미 필터에 등록돼 있어 인증은 걸려 있음).
- **Alternatives considered**: 없음(프로젝트 고정 구조).

## R7. 마이그레이션 — timestamp 버전 1건, FK 없음

- **Decision**: `V2026.08.01.**__report_table.sql` 1건 — `reports`(id, reporter_member_id, target_type VARCHAR(20), target_id, reason VARCHAR(20), detail VARCHAR(500) NULL, status, created_at, updated_at, UNIQUE(reporter_member_id, target_type, target_id)). FK 는 걸지 않는다.
- **Rationale**: 대상이 다형이라 FK 불가(plan Complexity Tracking). 소프트 삭제 공통 컬럼(status)은 BaseEntity 규약. 다른 마이그레이션과 순서 독립.
- **Alternatives considered**: review FK 부착(다형 확장 불가 — 기각).
