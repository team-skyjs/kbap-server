# Data Model: 리뷰 신고 (kb-129)

## 신규 테이블 `report`

테이블명은 기존 관례(단수 — `member`·`food`·`food_review`)를 따른다. Flyway 1건: `V2026.08.01.**__report_table.sql`.

| 컬럼 | 타입 | 제약 | 설명 |
|---|---|---|---|
| `id` | bigint | PK, AUTO_INCREMENT | BaseEntity 공통 |
| `reporter_member_id` | bigint | NOT NULL, FK → `member(id)` | 신고자 |
| `target_type` | varchar(20) | NOT NULL | 신고 대상 타입 코드 (`REVIEW`) |
| `target_id` | bigint | NOT NULL | 대상 콘텐츠 id — **FK 없음**(다형 대상, plan Complexity Tracking) |
| `reason` | varchar(20) | NOT NULL | 신고 사유 코드 |
| `detail` | varchar(500) | NULL | 상세 설명(선택) |
| `status` | enum('ACTIVE','DELETED') | NOT NULL DEFAULT 'ACTIVE' | BaseEntity 소프트 삭제 공통 |
| `created_at` / `updated_at` | datetime(6) | NOT NULL | BaseEntity 공통 |

인덱스·제약:

- `UNIQUE uk_report_reporter_target (reporter_member_id, target_type, target_id)` — 중복 신고를 DB 레벨에서 차단(동시 요청 최종 방어). 신고 취소가 없어 소프트 삭제 행과 UNIQUE 가 충돌하지 않는다.
- 제외 필터 조회는 (reporter_member_id, target_type) 프리픽스를 타므로 UNIQUE 인덱스가 커버 — 추가 인덱스 없음.
- 대상 리뷰가 삭제돼도 신고 행은 남는다(ON DELETE 없음 — 소프트 삭제 구조와 정합).

## 엔티티·enum (`com.kbap.common.domain.report`)

### `model/Report.kt` — 엔티티 (= 도메인 모델)

- `BaseEntity` 상속(자체 id·시각 없음). JPA 연관관계 없음 — 참조는 전부 `Long` id 값.
- 필드: `reporterMemberId: Long`, `targetType: ReportTargetType`(`@Enumerated(STRING)`, `@Column(length = 20)`), `targetId: Long`, `reason: ReportReason`(`@Enumerated(STRING)`, `@Column(length = 20)`), `detail: String?`(`@Column(length = 500)`).
- 도메인 메서드 없음(신고는 접수 후 불변 기록) — 상태 전이 없음.

### `model/ReportTargetType.kt`

- `REVIEW` 하나로 시작. 커뮤니티 게시글 등은 값 추가로 확장(spec Key Entities).

### `model/ReportReason.kt`

- `SPAM`(스팸·광고) · `ABUSE`(욕설·혐오) · `FALSE_INFO`(허위 정보) · `SEXUAL`(음란물) · `OTHER`(기타). 서버는 코드값만 저장·반환 — 번역 표시명 없음(FR-002).

### `ReportJpaRepository.kt`

- `existsByReporterMemberIdAndTargetTypeAndTargetId(...): Boolean` — 친절한 409 선조회.
- `findTargetIdsByReporterMemberIdAndTargetType(...): List<Long>` — 목록 제외 필터용 (`@Query select r.targetId ...`).

## 기존 변경

### `common.domain.review.ReviewJpaRepository`

- `findFoodReviewPage` 오버로드 추가: 기존 조건 + `and r.id not in :excludedIds`. 제외 목록이 비면 **기존 메서드를 그대로 호출**(research R3 — 빈 컬렉션 not in 금지). 쿼리는 제외 id 목록만 알고 신고 개념을 모른다.

### `common.core.error.ErrorCode`

| 코드 | HTTP | 의미 |
|---|---|---|
| `REPORT_SELF_TARGET` (`REPORT-001`) | 400 | 자기 콘텐츠 신고 거절 |
| `REPORT_DUPLICATED` (`REPORT-002`) | 409 | 같은 대상 중복 신고 |
| `REPORT_TARGET_NOT_FOUND` (`REPORT-003`) | 404 | 존재하지 않거나 삭제된 대상 |

### `ModuleBoundaryTest` 허용 맵

- `"report" to emptySet()` — report 도메인은 어떤 도메인도 컴파일 의존하지 않는다(대상은 id 값).

## 검증 규칙 (요청 경계 소유 — 헌법 V)

- `targetType`·`targetId`·`reason` 필수(`@field:NotNull`), `detail` 은 `@field:Size(max = 500)`.
- 미정의 enum 값(targetType·reason)은 역직렬화 단계에서 400.
- 자기 리뷰·중복·대상 없음 검증은 `api.report.ReportService` 유스케이스 소관(도메인 확정값 수신).
