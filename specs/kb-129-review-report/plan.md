# Implementation Plan: 리뷰 신고

**Branch**: `kb-129-review-report` | **Date**: 2026-08-01 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-129-review-report/spec.md`

## Summary

신고를 독립 리소스로 접수(`POST /api/v1/reports` — targetType·targetId·reason·detail)하고, 신고자 본인의 음식 리뷰 목록에서 신고한 리뷰를 제외한다. 저장은 일반 신고 모델(`reports` 테이블, UNIQUE(신고자, 대상 타입, 대상 id)) 하나로 하며, 이번 범위의 대상 타입은 REVIEW 뿐이다. 새 도메인 컨텍스트 `common.domain.report`(엔티티·enum·리포지토리) + API 기능 패키지 `com.kbap.api.report`(컨트롤러·유스케이스)로 구현하고, 리뷰 목록 제외는 기존 `api.review.ReviewService.getFoodReviewPage` 에 호출자 회원 id 를 추가해 처리한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi, Flyway

**Storage**: MySQL (신규 `reports` 테이블 — Flyway 마이그레이션, 스키마 owner=`:api`)

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼, MySQL Testcontainers(`:common` testFixtures `MySqlContainerConfig`), MockMvc

**Target Platform**: Linux 서버 (api bootJar — 운영 2대 무상태)

**Project Type**: web-service (모듈러 모놀리스 `:api` 만 변경, `:common` 에 영속 추가)

**Performance Goals**: 기존 리뷰 목록 keyset 페이징 성능 유지 — 제외 필터는 회원당 신고 id 목록(소량)의 NOT IN 한 번

**Constraints**: 중복 신고는 동시 요청에도 1건 보장(UNIQUE 제약이 최종 방어), 격리수준 조정 금지(프로젝트 고정 규칙), 페이지 개수 보정 재조회 금지

**Scale/Scope**: 신고는 회원당 소량(리뷰 UGC 신고). 엔드포인트 1개 신설 + 목록 조회 1개 수정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First (NON-NEGOTIABLE) | PASS | 모든 task 를 Red→Green→Refactor 로 진행. 도메인 단위(enum·엔티티), 영속 Testcontainers(UNIQUE 제약·제외 쿼리), MockMvc(성공·400·409·404·401·숨김/노출) 테스트를 구현 전 작성 |
| II. Bounded Contexts | PASS | 신규 컨텍스트 `common.domain.report` — 대상 참조는 `targetId: Long` 값. review 도메인에 의존하지 않으므로 `ModuleBoundaryTest` 허용 맵에 `"report" to emptySet()` 추가. 리뷰 존재·자기 리뷰 검증은 `com.kbap.api.report` 유스케이스가 조합 |
| III. Layered Dependency Direction | PASS | 변경은 `:common`(영속)·`:api`(기능 패키지)뿐. api→common 단방향 유지, seam·infra 무관 |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리는 `common.domain.report` 에 public. JPA 연관관계 없음(전부 id 값). 트랜잭션은 사용하는 서비스가 명시 선언. FK 는 다형 대상(target_type+target_id)이라 걸 수 없음 — 대상 존재 검증은 유스케이스가 수행(아래 Complexity Tracking) |
| V. Domain Content Language Policy | PASS | 신고 사유는 코드값만 저장·반환, 번역명 미제공(FE i18n — spec FR-002). lang 파라미터 없음. 음식 콘텐츠 번역 정책과 무관 |

**Post-Phase 1 재평가**: PASS — data-model·contracts 확정 후에도 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-129-review-report/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정·근거
├── data-model.md        # Phase 1 — reports 테이블·엔티티·enum
├── quickstart.md        # Phase 1 — 수동 검증 시나리오
├── contracts/
│   └── report-api.md    # Phase 1 — 신고 API·리뷰 목록 변경 계약
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/resources/db/migration/
└── V2026.08.01.**__report_table.sql              # reports 테이블 (신규)

common/src/main/kotlin/com/kbap/common/domain/report/
├── model/
│   ├── Report.kt                                  # 엔티티 (신규)
│   ├── ReportTargetType.kt                        # REVIEW (신규)
│   └── ReportReason.kt                            # SPAM·ABUSE·FALSE_INFO·SEXUAL·OTHER (신규)
└── ReportJpaRepository.kt                         # existsBy·신고 대상 id 조회 (신규)

common/src/main/kotlin/com/kbap/common/core/error/
└── ErrorCode.kt                                   # REPORT-001~003 추가 (수정)

api/src/main/kotlin/com/kbap/api/report/
├── ReportController.kt                            # POST /api/v1/reports (신규)
├── ReportApi.kt                                   # swagger 인터페이스 (신규)
├── ReportCreateRequest.kt                         # targetType·targetId·reason·detail (신규)
└── ReportService.kt                               # 신고 유스케이스 (신규)

api/src/main/kotlin/com/kbap/api/review/
├── ReviewController.kt                            # listFoodReviews 에 @AuthMemberId (수정)
├── ReviewApi.kt                                   # 시그니처 동기화 (수정)
└── ReviewService.kt                               # getFoodReviewPage 제외 필터 (수정)

common/src/main/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepository.kt                         # 제외 목록 오버로드 쿼리 (수정)

api/src/main/kotlin/com/kbap/api/core/config/
└── WebConfig.kt                                   # 인증 필터에 /api/v1/reports 등록 (수정)

api/src/test/kotlin/com/kbap/api/
├── architecture/ModuleBoundaryTest.kt             # 허용 맵 "report" to emptySet() (수정)
├── report/                                        # 신고 MockMvc·유스케이스 테스트 (신규)
└── review/                                        # 목록 숨김 테스트 보강 (수정)

common/src/test/kotlin/com/kbap/common/domain/report/  # 도메인·영속 테스트 (신규)
```

**Structure Decision**: 기존 모듈러 모놀리스 구조를 그대로 따른다 — 영속은 `:common` 의 컨텍스트 패키지(`common.domain.report`), API 전용 유스케이스·HTTP 경계는 `:api` 기능 패키지(`com.kbap.api.report`). 신규 모듈 없음.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `reports.target_id` 에 FK 없음 (원칙 IV 는 "FK 는 Flyway 스키마가 강제"가 기본) | 대상이 다형(target_type + target_id — REVIEW 이후 게시글 확장)이라 단일 FK 를 걸 수 없다 | 리뷰 전용 `review_reports` + FK 는 clarify 에서 기각(게시글 신고 추가 시 테이블 신설·데이터 이관 필요). 대상 존재 검증은 유스케이스(`ReportService`)가 리뷰 조회로 수행하고, 리뷰 삭제 후에도 신고 기록은 보존하는 요구(spec Edge Case)와도 FK ON DELETE 부재가 정합 |
