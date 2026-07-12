# Phase 0 Research: 프로필 부분 수정 (KB-124)

## R1. "미전송"과 "빈 배열"을 어떻게 구분하는가

**Decision**: 요청 DTO 의 모든 필드를 **nullable + 기본값 `null`** 로 선언한다.

```kotlin
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val avoidanceSubstanceCodes: List<String>? = null,
    val countryCode: String? = null,
    val appLanguage: String? = null,
)
```

- 필드 부재 → Jackson 이 기본값을 써서 `null` (jackson-module-kotlin 이 Kotlin 기본값을 존중한다)
- `[]` → `emptyList()` (null 이 아님) → **전부 해제**
- `["EGG"]` → 그 목록으로 교체

즉 `null`(유지)과 `emptyList()`(해제)가 타입 레벨에서 갈린다. FR-002 가 그대로 성립한다.

**Rationale**: 별도 래퍼 타입 없이 Kotlin 의 nullable 만으로 요구를 정확히 표현한다. spec 의 Assumption("명시적 `null` 전송 = 미전송")과도 일치한다 — 둘 다 `null` 로 도착하고 똑같이 "유지"로 처리된다.

**Alternatives considered**:
- `Optional<T>` / `JsonNullable<T>` 래퍼로 3상태(부재·null·값)를 구분 — 개별 항목 "비우기"가 필요할 때만 값어치가 있는데 그런 화면이 없다(spec Out of Scope). 요청·유스케이스·테스트가 전부 무거워진다. 기각.
- `@JsonAnySetter` 로 수신 키 집합을 따로 추적 — 같은 결과를 더 복잡하게 얻는다. 기각.

## R2. 병합을 어디서 하는가 — 도메인이냐 유스케이스냐

**Decision**: **도메인은 손대지 않는다.** 유스케이스에서 기존 프로필을 읽어 필드별로 해소한 뒤 **기존 공개 팩토리 `MemberProfile.of(...)`** 로 새 프로필을 만든다.

```kotlin
val current = member.profile
val merged = MemberProfile.of(
    nickname = input.nickname?.let { validatedNickname(it) } ?: current.nickname,
    avoidanceSubstanceCodes = input.avoidanceSubstanceCodes?.let { validatedCodes(it) } ?: current.avoidanceSubstanceCodes,
    spicinessPreference = current.spicinessPreference,
    countryCode = input.countryCode?.let { validatedCountry(it) } ?: current.countryCode,
    appLanguage = input.appLanguage?.let { validatedLanguage(it) } ?: current.appLanguage,
)
memberRepository.update(member.updateProfile(merged))
```

**Rationale**:
- `MemberProfile` 은 비공개 생성자 + `@ConsistentCopyVisibility`(비공개 `copy`)라 외부 필드 병합이 막혀 있지만, **`of(...)` 는 공개 팩토리**이고 모든 필드를 받는다. 병합에 필요한 건 이것뿐이다.
- 병합 규칙("미전송 = 유지")은 **API 계약의 성질**이지 도메인 불변식이 아니다. 도메인에 `merge(...)` 를 넣으면 도메인이 전송 여부라는 전송계층 개념을 알게 된다.
- 맵기 선호도는 지금도 `current` 에서 옮겨오고 있어(FR-008) 코드 모양이 그대로 이어진다.

**Alternatives considered**:
- `MemberProfile` 에 `fun merge(nickname: String?, ...)` 병합 팩토리 추가 — 도메인이 "null 은 유지"라는 전송 규약을 떠안는다. 기각.
- `copy` 를 공개로 전환 — 통제된 복제만 허용한다는 프로젝트 규약(도메인 객체 불변성)을 깨뜨린다. 기각.

## R3. 온보딩과 프로필 수정의 입력·검증 분리

**Decision**: 입력 타입을 **둘로 가른다**.

| | 온보딩 | 프로필 수정 |
|---|---|---|
| 요청 | `OnboardingRequest` (전 필드 필수 — **무변경**) | `ProfileUpdateRequest` (전 필드 nullable) |
| 입력 | `MemberProfileInput` (전 필드 non-null — **무변경**) | `ProfileUpdateInput` (**신규**, 전 필드 nullable) |
| 유스케이스 | `completeOnboarding(input)` — 전 필드 검증 후 새 프로필 | `update(input)` — 전달된 필드만 검증 후 기존 프로필과 병합 |

검증 함수는 **필드 단위로 쪼개** 두 경로가 공유한다: `validatedNickname`·`validatedCodes`·`validatedCountry`·`validatedLanguage`. 온보딩은 넷을 모두 부르고, 수정은 전달된 것만 부른다. 오류 코드·메시지(`OnboardingErrorCode`)는 그대로 재사용한다.

**Rationale**: 지금은 두 API 가 `MemberProfileInput` 하나와 `validatedProfile()` 하나를 공유한다. 그 공유 타입을 nullable 로 풀면 **온보딩의 "전 필드 필수"까지 함께 느슨해진다**(FR-007·SC-005 위반). 타입을 갈라야 컴파일러가 그 경계를 지켜 준다. 검증 로직은 필드 단위로 쪼개 중복 없이 공유한다.

**Alternatives considered**:
- 공유 입력 타입을 nullable 로 바꾸고 온보딩에서 런타임 null 검사 — 온보딩의 필수 조건이 타입에서 사라지고 런타임 검사로 강등된다. 기각.
- 온보딩 전용 검증을 따로 복제 — 검증 규칙이 두 벌로 갈라져 드리프트한다(티켓이 우려한 바로 그 문제). 기각.

## R4. 빈 요청(`{}`) 처리

**Decision**: 성공(200)으로 응답하고 프로필을 그대로 저장한다. 별도 분기·조기 반환을 두지 않는다.

**Rationale**: 모든 필드가 `null` 이면 병합 결과가 기존 프로필과 동일하므로 저장해도 값이 바뀌지 않는다(멱등). "아무것도 안 바뀐다"는 요구(FR-005)가 코드 없이 성립한다. 굳이 "변경 없음이면 저장 생략" 최적화를 넣을 만큼 잦은 경로가 아니다.

**Alternatives considered**: 빈 요청을 400 으로 거절 — 클라이언트가 "바뀐 필드만" 보내는 구조라 사용자가 아무것도 안 고치고 저장을 눌렀을 때 오류가 뜨는 셈이다. UX 상 나쁘다. 기각.

## R5. 검증 실패 시 부분 저장 방지

**Decision**: 병합된 프로필을 **다 만든 뒤에** 한 번만 저장한다. 검증은 병합 도중 필드별로 수행되며, 하나라도 실패하면 예외가 올라가 저장 자체가 일어나지 않는다.

**Rationale**: `update` 는 이미 `@Transactional` 이고 쓰기가 `memberRepository.update(...)` 단발이다. 예외가 나면 그 호출에 도달하지 않으므로 FR-004(부분 저장 없음)가 구조적으로 보장된다. 별도 방어 코드가 필요 없다.

## R6. Swagger 문서

**Decision**: `MemberApi` 의 `PATCH /me/profile` 설명을 **부분 수정 계약**으로 다시 쓰고 예시를 셋으로 늘린다 — (1) 닉네임·국가·언어만, (2) 기피 성분만, (3) 기피 성분 빈 배열(전부 해제). "미전송 = 유지, 빈 배열 = 전부 해제"를 본문에 명시한다.

**Rationale**: 이 API 의 가장 헷갈리는 지점이 빈 배열과 미전송의 차이다. 예시로 못 박지 않으면 클라이언트가 또 전 필드를 실어 보낸다. 온보딩 쪽 문서는 건드리지 않는다.
