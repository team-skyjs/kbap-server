# Phase 1 Data Model: 프로필 부분 수정 (KB-124)

## 스키마 변경: 없음

Flyway 마이그레이션이 없다. 저장 형태는 그대로다.

| 저장 위치 | 항목 | 비고 |
|---|---|---|
| `member.nickname` 컬럼 (`VARCHAR(30) NULL`) | 닉네임 | 무변경 |
| `member.profile` JSON 컬럼 | 기피 성분 코드 목록·맵기 선호도·국가·앱 언어 | 무변경 (`MemberProfileJson` 이 null 을 그대로 왕복한다) |

`MemberJpaEntity.applyProfile(domain)` 은 도메인 프로필을 통째로 덮어쓴다 — **병합은 유스케이스에서 끝난 뒤** 완성된 프로필이 내려오므로 영속 계층은 손대지 않는다.

## 도메인 (`:core:member`) — 변경 없음

```kotlin
@ConsistentCopyVisibility
data class MemberProfile private constructor(
    val nickname: String?,
    val avoidanceSubstanceCodes: Set<AvoidanceSubstanceCodeRef>,
    val spicinessPreference: Int,
    val countryCode: CountryCode?,
    val appLanguage: LanguageCode?,
) {
    companion object {
        fun of(nickname, avoidanceSubstanceCodes, spicinessPreference, countryCode, appLanguage): MemberProfile
        fun empty(): MemberProfile
    }
}
```

닉네임·국가·언어가 **이미 nullable** 이고 `of(...)` 가 공개 팩토리라, 병합에 필요한 것은 전부 갖춰져 있다. `Member.updateProfile(profile)` 도 그대로 쓴다.

## 애플리케이션 (`:application:client`) — 입력 타입 분리

```kotlin
// dto/MemberProfileInput.kt — 온보딩 전용. 무변경(전 필드 non-null).
data class MemberProfileInput(
    val memberId: Long,
    val nickname: String,
    val avoidanceSubstanceCodes: List<String>,
    val countryCode: String,
    val appLanguage: String,
)

// dto/ProfileUpdateInput.kt — 신규. 전 필드 nullable = "미전송".
data class ProfileUpdateInput(
    val memberId: Long,
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,   // null=유지, []=전부 해제
    val countryCode: String? = null,
    val appLanguage: String? = null,
)
```

`MemberProfileUseCase`:
- `completeOnboarding(input: MemberProfileInput)` — 무변경(전 필드 검증 → 새 프로필 → 온보딩 완료 전이).
- `update(input: ProfileUpdateInput)` — **시그니처 변경**. 전달된 필드만 검증하고 기존 프로필과 병합해 `MemberProfile.of(...)` 로 재조립.
- 기존 `validatedProfile(input, member)` 를 **필드 단위 검증 함수 4개**로 분해해 두 경로가 공유한다:
  `validatedNickname(raw): String` · `validatedCodes(raw): Set<AvoidanceSubstanceCodeRef>` · `validatedCountry(raw): CountryCode` · `validatedLanguage(raw): LanguageCode`.
  오류 코드는 기존 `OnboardingErrorCode`(전부 400) 그대로: `INVALID_NICKNAME` · `INVALID_AVOIDANCE_SUBSTANCE_CODE` · `INVALID_COUNTRY_CODE` · `UNSUPPORTED_APP_LANGUAGE`.

## Web (`:app:api`) — 요청 DTO nullable 전환

```kotlin
// OnboardingRequest.kt — 무변경(전 필드 필수)
data class OnboardingRequest(
    val nickname: String,
    val avoidanceSubstanceCodes: List<String> = emptyList(),
    val countryCode: String,
    val appLanguage: String,
) { fun toInput(memberId: Long): MemberProfileInput }

// ProfileUpdateRequest.kt — 전 필드 nullable + 기본값 null
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
) { fun toInput(memberId: Long): ProfileUpdateInput }
```

두 요청 타입은 지금 내용이 같지만 이 변경으로 **의도적으로 갈라진다**(온보딩=전부 필수, 수정=전부 선택).

## 병합 규칙 (핵심)

| 요청 필드 | 도착 값 | 결과 |
|---|---|---|
| 필드 부재 | `null` | 기존 값 **유지** |
| 명시적 `null` | `null` | 기존 값 **유지** (부재와 동일) |
| `nickname: "길동이"` | `"길동이"` | 검증 후 **교체** (앞뒤 공백 제거) |
| `avoidanceSubstanceCodes: []` | `emptyList()` | **전부 해제** |
| `avoidanceSubstanceCodes: ["EGG"]` | `["EGG"]` | 검증·중복 제거 후 **교체** |
| 맵기 선호도 | (API 미노출) | 항상 기존 값 **보존** |

검증 실패 시 예외가 올라가 **저장 자체가 일어나지 않는다** — 부분 저장 없음.
