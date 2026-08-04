# Data Model: 회원 프로필 JSON 컬럼 평탄화 (KB-297)

## member 테이블 (변경)

| 컬럼 | 타입 | 제약 | 변경 |
|------|------|------|------|
| `profile` | json | NOT NULL | **삭제** (drop 마이그레이션) |
| `spiciness_preference` | ENUM('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME') | NOT NULL DEFAULT 'SKIP' | **추가** |
| `country_code` | VARCHAR(2) | NULL | **추가** |
| `profile_image_url` | VARCHAR(512) | NULL | **추가** |
| `avoidance_substance_codes` | json | NOT NULL | **추가** — 코드 문자열 배열(예: `["PEANUT","SHRIMP"]`), 빈 배열 허용 |

나머지 컬럼(nickname 포함)은 불변. nickname 은 이미 개별 컬럼. 별도 테이블은 만들지 않는다(R2 — 2026-08-05 결정).

## 엔티티 매핑 (Member.kt)

```kotlin
@Enumerated(EnumType.STRING)
@Column(name = "spiciness_preference", nullable = false,
    columnDefinition = "ENUM('SKIP','NONE','MILD','MEDIUM','HOT','EXTREME') default 'SKIP'")
var spicinessPreference: SpicinessPreference = SpicinessPreference.SKIP,

@Column(name = "country_code", length = 2)
var countryCode: String? = null,

@Column(name = "profile_image_url", length = 512)
var profileImageUrl: String? = null,

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "avoidance_substance_codes", nullable = false)
var avoidanceSubstanceCodes: List<String> = emptyList(),
```

- `AvoidanceSubstanceCodeRef` 는 지금 그대로(순수 값 클래스) — 매핑 변경 없음, `MemberProfile` 조립 시 래핑
- `profile` getter: `MemberProfile.of(nickname, avoidanceSubstanceCodes.map { AvoidanceSubstanceCodeRef(it) }.toSet(), spicinessPreference, CountryCode.from(countryCode), profileImageUrl)` — 로드 시 trimStart 정규화 제거(R4)
- `updateProfile(profile: MemberProfile)`: 4개 필드 대입 — dirty checking, save() 호출 없음
- `MemberProfileJson` 삭제 (프로필 복합 JSON 직렬화 소멸 — JSON 매핑은 문자열 배열 하나만 남음)

## 검증 규칙 (불변 — MemberProfile 이 계속 소유)

- 닉네임: trim 후 blank 금지
- 회피 성분 코드: 카탈로그(`AvoidanceSubstanceCode`) 존재 검증
- 국가 코드: `CountryCode.from` 검증
- 이미지 경로: trimStart('/')·길이 512·절대 URL 거부 (쓰기 시)

## 상태 전이

변화 없음 — 온보딩 완료 플래그·소프트 삭제(BaseEntity.status)·member_status 기존 그대로. 탈퇴 시 코드 행은 잔존(member 행이 DELETED — 스펙 엣지 케이스 "탈퇴 회원 데이터 이전 포함" 충족).

## 백필 매핑 (JSON → 신규 구조)

| JSON 경로 | 대상 | 변환 |
|-----------|------|------|
| `$.spicinessPreference` | `spiciness_preference` | 값 그대로 (전 회차 마이그레이션이 6단계 enum 문자열 보장) |
| `$.countryCode` | `country_code` | JSON null → SQL NULL |
| `$.profileImageUrl` | `profile_image_url` | `TRIM(LEADING '/')` — legacy 슬래시 정규화. JSON null → SQL NULL |
| `$.avoidanceSubstanceCodes` | `avoidance_substance_codes` | 배열 그대로 이관(`JSON_EXTRACT`). 속성 결손 → `JSON_ARRAY()`(빈 배열) |
