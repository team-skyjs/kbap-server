# Research: 사용자 프로필 맵기 설정 ENUM 전환 (Phase 0)

## R1. enum 의 위치와 이름

- **Decision**: `com.kbap.common.domain.member.model.SpicinessPreference` — SKIP·NONE·MILD·MEDIUM·HOT·EXTREME 6값.
- **Rationale**: 맵기 *선호* 는 member 컨텍스트 소유 개념. 음식 맵기 *점수*(`Food.spiciness: Int` 0~10, 미조사 -1)와 이름부터 분리해 혼동을 차단한다. 기존 공유 `Spiciness.RANGE`(`common.domain` 루트)는 이번 변경 후 food 전용으로만 쓰이지만 이동은 범위 밖(별도 정리 대상).
- **Alternatives considered**: ① food 와 공유하는 단일 맵기 enum — 기각(점수 0~10 과 선호 6단계는 다른 개념·다른 라이프사이클). ② `common.domain` 루트 공유 vocabulary — 기각(참조 컨텍스트가 member 뿐).

## R2. 요청 표현 — Jackson enum 직접 바인딩 vs 문자열 수신 후 도메인 검증

- **Decision**: 요청 DTO 는 **문자열(String)로 수신**하고 `MemberProfile`(도메인)이 `SpicinessPreference` 로 검증·변환한다. 미지 값은 기존 코드 **MEMBER-009**(`INVALID_SPICINESS_PREFERENCE`) 400 으로 거절하고 메시지만 6단계 기준으로 갱신한다.
- **Rationale**: ① 기존 패턴 일치 — `countryCode`(String→`CountryCode`, MEMBER-00x)·`avoidanceSubstanceCodes`(String→코드 검증)와 동일한 형태로 `MemberProfile.updatedWith` 검증 경로에 합류한다. ② Jackson enum 직접 바인딩이면 오류가 `HttpMessageNotReadableException` → COMMON-002("요청 본문을 해석할 수 없습니다")로 뭉개져 클라이언트가 필드 특정 불가. 에러 코드 재사용(MEMBER-009)으로 클라이언트 분기 안정성 유지(코드 불변·메시지 자유 변경 규약).
- **Alternatives considered**: DTO 필드를 `SpicinessPreference` 타입으로 선언 — 기각(오류 코드가 COMMON-002 로 퇴화, 검증 소유가 요청 경계·도메인 규약과 어긋남).

## R3. 영속 표현 — profile JSON 안의 값 형태

- **Decision**: `MemberProfileJson.spicinessPreference` 를 **enum 이름 문자열**로 저장하고, 필드 타입은 `SpicinessPreference`(기본값 `SKIP`)로 선언한다. Jackson 이 이름 문자열 ↔ enum 을 왕복한다.
- **Rationale**: 저장·조회가 항상 검증된 enum 값만 오간다(FR-006 — 정수 표현 잔존 금지). 이관 후 비정상 문자열이 남아 있으면 역직렬화가 즉시 실패해 조용한 유실이 없다(스펙 edge case). 필드 결손(legacy 결손 데이터)은 기본값 `SKIP` 처리 — 스펙 "결손=미설정" 과 일치.
- **Alternatives considered**: String 저장 + `toDomain` 에서 lookup — 기각(`CountryCode.from` 은 null 허용 개념이라 silent-null 이 어울리지만, 맵기는 non-null 필수 값이라 enum 타입 선언이 더 짧고 실패도 시끄럽다).

## R4. 데이터 이관 — Flyway 마이그레이션 전략

- **Decision**: 단일 마이그레이션 `V<생성시각>__member_spiciness_enum.sql` 에서 `member.profile` JSON 을 `JSON_SET` 으로 재작성한다.
  1. 속성 결손 행 → `'SKIP'` 명시 기입.
  2. 정수 행 → `CASE` 매핑: `-1→SKIP, 0→NONE, 1~3→MILD, 4~6→MEDIUM, 7~8→HOT, 9~10→EXTREME` (spec Assumptions 기본안).
  3. **가드 문장**: 매핑 후에도 enum 이름 6종이 아닌 값이 남은 행이 있으면 마이그레이션이 실패하도록 한다(`UPDATE member SET profile = NULL WHERE <비정상 잔존>` — `profile` NOT NULL 제약 위반으로 시끄럽게 중단; 잔존 0행이면 no-op).
- **Rationale**: 컬럼이 아니라 JSON 속성이므로 DDL 없이 DML 재작성으로 충분. 가드로 "조용한 유실 금지"(스펙 edge case)를 DB 레벨에서 보장. 다른 마이그레이션과 순서 독립(해당 속성만 다룸 — out-of-order 규약 충족).
- **Alternatives considered**: ① `CASE ... ELSE 'SKIP'` 흡수 — 기각(범위 밖 비정상 값을 조용히 SKIP 으로 유실). ② 전용 컬럼 신설 — 기각(프로필은 JSON 단일 컬럼 구조가 기존 결정, 이번 이슈 범위 아님).

## R5. 하위 호환

- **Decision**: 이중 수용(정수도 받고 enum 도 받는 기간) 없음. 배포 즉시 정수 입력은 400.
- **Rationale**: 스펙 Assumptions — 클라이언트가 같은 기획 변경으로 동시 전환. Swagger 문서·예시를 같은 PR 에서 갱신(FR-004)해 계약 단일화.
- **Alternatives considered**: Int|String 이중 수용 — 기각(YAGNI, 전환기 종료 시점 관리 비용만 추가).

## R6. 영향 범위 확정 (코드 스캔 결과)

member 밖에서 `spicinessPreference` 를 소비하는 곳은 **관리자 회원 조회(`AdminMemberQueryService.AdminMemberDetailView`) 하나**뿐이다 — 음식 추천·위험도 판정 등 다른 도메인 계산에 쓰이지 않음(스펙 Assumption 검증 완료). 변경 대상: `MemberProfile`·`MemberProfileJson`·`MemberProfileInput`·`ProfileUpdateInput`·`MyProfileResult`·`OnboardingRequest`·`ProfileUpdateRequest`·`MyProfileResponse`·`MemberApi`(Swagger)·`AdminMemberQueryService`·`ErrorCode`(메시지)·신규 enum·신규 마이그레이션 + 정수를 쓰는 member/admin/scenario 테스트 전수.
