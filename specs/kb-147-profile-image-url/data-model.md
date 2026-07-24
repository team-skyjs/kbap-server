# Data Model: 프로필 사진 URL·맵기 선호 필드 추가 (KB-147)

## DB 스키마

**변경 없음.** `member.profile` JSON 컬럼(기존)에 키 하나가 추가될 뿐이다 — 기존 row 는 키 부재 = 미설정(null). Flyway 마이그레이션 0건.

## 도메인 모델 변경 (전부 기존 타입에 필드 추가)

### MemberProfile (값 객체 — `com.kbap.domain.member.model`)

| 필드 | 타입 | 추가/기존 | 규칙 |
|------|------|-----------|------|
| profileImageUrl | `String?` | **추가** | null = 미설정(기본). 유효값은 서비스 검증을 통과한 https URL |
| spicinessPreference | `Int` | 기존 (무변경) | 0~10·기본 5 — 이번에 온보딩 입력·수정·조회 노출 경로만 개방 |
| nickname 외 기존 필드 | — | 기존 | 무변경 |

- `of(...)`·`empty()` 에 `profileImageUrl` 파라미터 추가(`empty()` 는 null).
- 값 자체 불변식(spiciness `require` 같은)은 두지 않는다 — URL 유효성은 허용 호스트라는 **환경 설정 의존 규칙**이라 값 객체가 아닌 `MemberService` 소유.

### MemberProfileJson (JSON 매핑 — internal 상세)

| 필드 | 타입 | 기본값 | 비고 |
|------|------|--------|------|
| profileImageUrl | `String?` | `null` | 기본값 덕에 기존 row(키 부재) 역직렬화 호환. `toDomain`/`from` 에 1:1 매핑 추가 |

### 검증 규칙 (MemberService.validatedImageUrl — research R3·R4)

1. 공백 trim 후 빈 문자열 → 컨텍스트별: 온보딩 = null(미설정), 부분 수정 = null(제거).
2. 길이 ≤ 512.
3. `java.net.URI` 파싱 성공 · 스킴 `https` · 호스트 존재.
4. `kbap.member.profile-image-allowed-hosts` 비어 있지 않으면 호스트 정확 일치 필수.
5. 불합격 → `BusinessException(INVALID_PROFILE_IMAGE_URL)` (MEMBER-008, 400).

### 검증 규칙 (MemberService.validatedSpiciness — research R6)

- 전송값이 0~10 밖 → `BusinessException(INVALID_SPICINESS_PREFERENCE)` (MEMBER-009, 400). 미전송(null)은 기존 값 유지(신규 회원 기본 5). `MemberProfile` init 의 `require` 는 최후 방어선으로 유지.

### 상태 전이 (profileImageUrl)

```text
미설정(null) --온보딩/수정에 유효 URL--> 설정(url)
설정(url)   --수정에 새 유효 URL-->      설정(새 url)
설정(url)   --수정에 빈 문자열-->        미설정(null)
임의 상태   --수정에 필드 미전송-->      변화 없음(유지)
```

## DTO 변경 (필드 추가만)

| 타입 | 위치 | 추가 필드 | 시맨틱 |
|------|------|-----------|--------|
| `MemberProfileInput` | domain dto | `profileImageUrl: String? = null` · `spicinessPreference: Int? = null` | 온보딩 선택 입력 (맵기 null=기본 5 유지) |
| `ProfileUpdateInput` | domain dto | `profileImageUrl: String? = null` · `spicinessPreference: Int? = null` | 사진: null=유지 · blank=제거 · 값=교체 / 맵기: null=유지 · 값=검증 후 교체 |
| `MyProfileResult` | domain dto | `profileImageUrl: String?` · `spicinessPreference: Int` | 조회 결과 |
| `OnboardingRequest` | app:api | `profileImageUrl: String? = null` · `spicinessPreference: Int? = null` | → MemberProfileInput |
| `ProfileUpdateRequest` | app:api | `profileImageUrl: String? = null` · `spicinessPreference: Int? = null` | → ProfileUpdateInput |
| `MyProfileResponse` | app:api | `profileImageUrl: String?` · `spicinessPreference: Int` | ← MyProfileResult |

## 설정

| 프로퍼티 | 기본값 | 환경 |
|----------|--------|------|
| `kbap.member.profile-image-allowed-hosts` | (빈 값 — 형식 검증만) | prod(·staging)에 CloudFront CDN 호스트 등록, local/dev/테스트는 미설정 |

## ErrorCode (`:core`)

| 상수 | 코드 | HTTP | 메시지 |
|------|------|------|--------|
| `INVALID_PROFILE_IMAGE_URL` | MEMBER-008 | 400 | 프로필 사진 URL 형식이 올바르지 않습니다 |
| `INVALID_SPICINESS_PREFERENCE` | MEMBER-009 | 400 | 맵기 선호는 0~10 사이여야 합니다 |
