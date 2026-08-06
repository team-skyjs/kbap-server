# Research: 회원 프로필 JSON 컬럼 평탄화 (KB-297)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 설계 갈림길 4건을 결정으로 기록한다.

## R1. 단일 값 항목의 컬럼 타입

**Decision**: member 테이블에 다음 3컬럼 추가.

| 컬럼 | 타입 | 제약 | 근거 |
|------|------|------|------|
| `spiciness_preference` | `ENUM('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME')` | `NOT NULL DEFAULT 'SKIP'` | 기존 enum 컬럼 선례(`provider`·`member_status`)와 동일 패턴. 도메인 기본값(SpicinessPreference.SKIP)과 일치 |
| `country_code` | `VARCHAR(2)` | `NULL` | `CountryCode` enum 은 ISO 3166-1 alpha-2(2자). 미설정(null)이 도메인 정상값 |
| `profile_image_url` | `VARCHAR(512)` | `NULL` | `MemberProfile.PROFILE_IMAGE_PATH_MAX_LENGTH = 512` 와 일치. 미설정=null(기본 이미지 경로는 백필 마이그레이션이 이미 채워둠 — 신규 가입 직후만 null) |

**Rationale**: 엔티티 `@Column(length/columnDefinition)` 을 Flyway DDL 과 일치시키는 기존 규약(컬럼 정의 MySQL 기준 고정). country_code 를 ENUM(190여 값)으로 하지 않는 이유 — 국가 추가 시 DDL 변경이 필요해지고, 검증은 이미 도메인(`CountryCode.from`)이 소유한다.

**Alternatives considered**: `spiciness_preference VARCHAR(10)` — 기존 member 테이블의 enum 컬럼 선례와 어긋나 기각.

## R2. 회피 성분 코드 목록의 매핑 방식

**Decision** (2026-08-05 사용자 확정): member 테이블의 **JSON 배열 컬럼** `avoidance_substance_codes`(`json NOT NULL`). 엔티티는 `@JdbcTypeCode(SqlTypes.JSON) var avoidanceSubstanceCodes: List<String>` — 기존 `profileJson` 과 동일한 매핑 메커니즘을 문자열 리스트에만 적용한다. 별도 테이블·`@ElementCollection`·`@Embeddable` 을 만들지 않는다.

**Rationale**:
- 코드 목록은 항상 회원 단위로 통째로 읽고 쓴다 — 코드 단위 역방향 조회("성분 X 를 기피하는 회원") 요구가 없어 행 분리의 이득(인덱스·FK)이 실사용처가 없다.
- 목록이 곧 값 하나(회원의 기피 집합)라는 도메인 의미와 일치하고, 컬렉션 테이블 관리(EAGER·@BatchSize·N+1 고려)가 통째로 사라진다 — 최단 diff.
- `Member.profile` getter / `updateProfile` 단일 쓰기 경로 유지 — 소비처 7곳·MemberService 수정 0건.
- 다른 프로필 항목과 섞인 기존 구조와 달리, 이 컬럼은 코드 배열 하나만 담으므로 "JSON 안에 이질 필드가 섞여 스키마를 DB 가 못 본다"는 원래 문제가 재발하지 않는다.

**감수하는 것**: 코드 형식·카탈로그 정합을 DB 가 강제하지 못한다(도메인 검증 `validatedCodes` 가 유일 방어 — 기존과 동일 수위). 코드 단위 집계가 필요해지면 그때 테이블 분리를 재검토한다.

**Alternatives considered**:
1. **별도 테이블 + `@ElementCollection` 값 컬렉션** — 코드 단위 조회·FK 강제가 가능하지만 현재 요구가 없고, 컬렉션 로드 전략(EAGER·@BatchSize) 관리 비용만 추가돼 기각(초기 결정을 사용자 결정으로 대체).
2. **별도 엔티티 + 리포지토리** — profile getter 가 코드를 품을 수 없어 소비처 전부가 2-쿼리 조립로 바뀜. diff 최대라 기각.

## R3. 마이그레이션 구성 — 3파일 분리

**Decision**: Flyway 마이그레이션 3개(점 구분 timestamp 버전, 생성 시각 순서대로):
1. **schema** — `ALTER TABLE member ADD COLUMN` 4종(단일 값 3 + `avoidance_substance_codes` JSON).
2. **backfill** — 단일 UPDATE: 단일 값 3종은 `JSON_UNQUOTE(JSON_EXTRACT(...))`(이미지 경로는 `TRIM(LEADING '/')` 로 legacy 슬래시 정규화), 코드 목록은 `JSON_EXTRACT(profile, '$.avoidanceSubstanceCodes')` 배열을 그대로 이관(속성 결손 시 빈 배열). 소프트 삭제 회원 포함 전 행 대상.
3. **nullable 전환** — `ALTER TABLE member MODIFY COLUMN profile json NULL`. 신규 코드가 이 컬럼을 매핑하지 않으므로(신규 가입 행 NULL) NOT NULL 을 해제한다. **컬럼 drop 은 평탄화 안정화 확인 후 후속 릴리스로 분리**(2026-08-06 결정 — 초기 drop 안을 대체. 구코드가 JSON 을 계속 읽을 수 있어 롤링 윈도우·롤백 리스크가 사라진다).

**Rationale**: 단계 분리로 FR-007 충족 — backfill 이 실패하면 drop 은 실행되지 않아 JSON 원본이 보존되고 재시도 가능하다(MySQL DDL 은 비트랜잭션이므로 한 파일에 섞으면 부분 적용 위험). 같은 브랜치에서 생성돼 timestamp 순서가 보장되므로 상호 순서 의존이 병렬 브랜치 out-of-order 규칙과 충돌하지 않는다.

**배포 주의(롤링 윈도우)**: 컬럼이 남아 있으므로 구 인스턴스의 member SELECT 는 계속 동작한다. 유일한 엣지는 윈도우 중 **새 코드가 만든 회원(profile NULL)** 을 구 코드가 읽는 경우 — 회원가입 직후 조회가 구 인스턴스로 라우팅되는 짧은 구간뿐이라 감수한다.

**Alternatives considered**: 단일 파일 통합 — 실패 시 부분 적용 상태(컬럼은 생겼는데 백필 안 됨 + DDL 은 롤백 불가)로 재시도 복잡. 기각.

## R4. 로드 시 정규화(trimStart('/'))의 거취

**Decision**: 제거한다. `MemberProfileJson.toDomain` 이 하던 로드 시 선행 슬래시 제거는 backfill 마이그레이션의 `TRIM(LEADING '/')` 이 데이터 자체를 정규화하므로 불필요해진다. 쓰기 경로 검증(`validatedImagePath` 의 trimStart + 형식 검증)은 유지한다.

**Rationale**: 정규화를 데이터에 한 번 적용하면 읽기 경로 방어 코드가 죽은 코드가 된다. 쓰기 검증이 유지되므로 새 데이터는 항상 무슬래시로 저장된다.

**Alternatives considered**: 로드 정규화 유지 — 백필 후 도달 불가 분기라 기각(테스트 불가능한 방어 코드).

## 폐기 항목 처리 참고

- 구 `appLanguage` 속성은 `V2026.07.23.16.06.50` 마이그레이션이 이미 JSON 에서 제거했고, `@JsonIgnoreProperties("appLanguage")` 방어가 남아 있다 — JSON 표현 자체가 사라지므로 함께 소멸(별도 작업 불필요).
- `MemberControllerTest` 649행이 `V2026.07.20.14.04.38__backfill_default_profile_image.sql` 을 리소스로 읽는다 — 파일명 불변이므로 영향 없음. 단 424행 근방의 profile JSON 컬럼 직접 조회 검증은 컬럼 기반으로 교체 대상.
