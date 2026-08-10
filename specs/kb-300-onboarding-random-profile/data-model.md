# Phase 1 Data Model: 온보딩 시 닉네임·프로필 사진 랜덤 지정

## 스키마 변경

**없음.** Flyway 마이그레이션 0건. `member.nickname`(VARCHAR 30)·`member.profile_image_url`(VARCHAR 512) 컬럼과 제약이 그대로다. 바뀌는 것은 두 컬럼이 **채워지는 경로**뿐이다.

## 신규: `OnboardingProfileDefaults`

**위치**: `common/src/main/kotlin/com/kbap/common/domain/member/model/OnboardingProfileDefaults.kt`

**성격**: 도메인 상수 + 정책 함수. 영속 대상 아님, 빈(bean) 아님.

| 멤버 | 타입 | 설명 |
|------|------|------|
| `PROFILE_IMAGE_PATHS` | `List<String>` | 프로필 이미지 스토리지 경로 후보 — 아래 6종 확정 |
| `randomProfileImagePath()` | `String` | `PROFILE_IMAGE_PATHS.random()` |
| `randomNickname()` | `String` | `NICKNAME_CODE_CHARS` 에서 6자 추첨 (고정 후보 목록·접두어 없음 — research R5) |
| `NICKNAME_CODE_CHARS` | `String` (private) | `"ABCDEFGHJKLMNPQRSTUVWXYZ23456789"` — 혼동 문자 `0`·`O`·`1`·`I` 제외 |

**`PROFILE_IMAGE_PATHS` 확정값** (research R6):

```
images/webp/default_profile/avatar-amber.png
images/webp/default_profile/avatar-navy.png
images/webp/default_profile/avatar-olive.png
images/webp/default_profile/avatar-orange.png
images/webp/default_profile/avatar-plum.png
images/webp/default_profile/avatar-teal.png
```

선행 `/` 없이 선언한다 — `MemberProfile.validatedImagePath` 가 저장 시 `trimStart('/')` 하므로, 슬래시를 붙이면 상수와 저장값이 어긋나 "지정값이 후보 안에 있는가" 검증이 깨진다.

**불변식** (테스트가 강제 — research R4):

- `randomNickname()` 반환값: `^[A-HJ-NP-Z2-9]{6}$` 일치, 길이 6 (`member.nickname` 컬럼 상한 30 이내), 공백 없음. 반복 호출 시 서로 다른 값이 나온다.
- `PROFILE_IMAGE_PATHS`: `isNotEmpty()`, 각 원소가 절대 URL 아님(`ImageUrls.isAbsoluteUrl == false`), 선행 `/` 없음, 길이 1..512.
- 근거: 이 불변식은 `MemberProfile.validatedNickname`·`validatedImagePath` 가 런타임에 요구하는 것과 같다. 어기면 온보딩이 400 으로 실패한다.

## 변경: `MemberProfileInput`

**위치**: `common/src/main/kotlin/com/kbap/common/domain/member/dto/MemberProfileInput.kt`

| 필드 | 변경 전 | 변경 후 | 의미 |
|------|---------|---------|------|
| `memberId` | `Long` | `Long` | 불변 |
| `nickname` | `String` | `String?` (기본 `null`) | **`null` = 서버가 후보에서 추첨** |
| `avoidanceSubstanceCodes` | `List<String>` | `List<String>` | 불변 |
| `countryCode` | `String` | `String` | 불변 — 여전히 필수 |
| `profileImageUrl` | `String` | `String?` (기본 `null`) | **`null` = 서버가 후보에서 추첨** |
| `spicinessPreference` | `String` | `String` | 불변 — 여전히 필수 |

`null = 서버 지정` 규약은 이 dto 와 `OnboardingRequest` 의 주석·swagger 문서가 명시한다. null 이 만들어지는 조건(`X-App-Version` 헤더 존재)은 web 계층이 소유한다.

## 변경: `MemberService.completeOnboarding`

정책 반영 지점. 트랜잭션 경계(`@Transactional`)는 그대로다.

```
completeOnboarding(input):
    member = getMember(input.memberId)          # 없으면 MEMBER_NOT_FOUND
    member.completeOnboarding(
        nickname         = input.nickname         ?: OnboardingProfileDefaults.randomNickname(),
        profileImageUrl  = input.profileImageUrl  ?: OnboardingProfileDefaults.randomProfileImagePath(),
        avoidanceSubstanceCodes = input.avoidanceSubstanceCodes,
        spicinessPreference     = input.spicinessPreference,
        countryCode             = input.countryCode,
    )
```

`Member.completeOnboarding` 은 **변경하지 않는다** — 시그니처의 non-null 계약이 그대로 유지되고, 이미 온보딩한 회원에 대한 `ONBOARDING_ALREADY_COMPLETED` 가드도 그대로다. 추첨은 가드 통과 여부와 무관하게 인자 평가 시점에 일어나지만 부작용이 없으므로(순수 함수) 문제되지 않는다.

## 변경: `OnboardingRequest`·`MemberController` (2026-08-10 개정 2 — 헤더 분기)

**위치**: `api/src/main/kotlin/com/kbap/api/member/OnboardingRequest.kt`·`MemberController.kt`

| 필드 | 타입 | 헤더 >= 1.1.0 | 미전송·미만·형식 오류 |
|------|------|-----------|-----------|
| `nickname` | `String?` (기본 `null`) | 무시 — 서버 지정 | **필수** — 누락 시 400 `COMMON-002` |
| `avoidanceSubstanceCodes` | `List<String>` (기본 `emptyList()`) | 선택 | 선택 |
| `countryCode` | `String` | 필수 | 필수 |
| `profileImageUrl` | `String?` (기본 `null`) | 무시 — 서버 지정 | **필수** — 누락 시 400 `COMMON-002` |
| `spicinessPreference` | `String` | 필수 | 필수 |

`MemberController` 가 `@RequestHeader("X-App-Version", required = false)` 를 받아 `AppVersion.parseOrNull(헤더) >= 1.1.0` 판정으로 `toInput(memberId, serverAssignsProfile)` 을 분기한다. `serverAssignsProfile = true` 면 두 필드를 null 로 넘겨 `MemberService` 가 추첨으로 채우고, `false` 면 두 필드 null 시 `BusinessException(INVALID_REQUEST)` — Jackson 역직렬화 거절이 담당하던 종전 400 `COMMON-002` 를 동일 코드로 유지한다. `api.core.AppVersion` 은 semver 파싱(빠진 파트 0, 음수·오타는 null)과 숫자 비교만 담당한다.

## 상태 전이

| 시점 | `onboardingCompleted` | `nickname` | `profile_image_url` |
|------|----------------------|-----------|--------------------|
| 소셜 가입 직후(`Member.signUp`) | `false` | `null` | `null` |
| 온보딩 완료 — 헤더 없음·1.1.0 미만(구 앱) | `true` | 클라이언트 전송값 | 클라이언트 전송값 |
| 온보딩 완료 — 헤더 >= 1.1.0(신 앱) | `true` | 생성 코드(`K7M2XB`) | 후보 추첨값 |
| 이후 프로필 수정 | `true` | 사용자 지정값 | 사용자 지정값 |
| 온보딩 재시도 | `true` (불변) | 불변 | 불변 — `ONBOARDING_ALREADY_COMPLETED` |

## 영향 없는 것

- `MyProfileResult`·`MyProfileResponse` — 응답 형식 무변경. `ImageUrls.resolve` 가 공개 베이스 URL 을 붙이는 방식도 동일(FR-010).
- `ProfileUpdateInput`·`MemberProfile` — 검증 규칙·부분 수정 규약 무변경.
- `ErrorCode` — 신규 코드 없음. 기존 `MEMBER_NOT_FOUND`·`ONBOARDING_ALREADY_COMPLETED`·`INVALID_*` 그대로.
