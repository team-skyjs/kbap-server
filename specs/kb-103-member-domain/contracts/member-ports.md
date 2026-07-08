# Contracts: 회원 도메인 port·서비스 계약

이 기능은 HTTP API 를 노출하지 않는다(R1 — 로그인 API 는 KB-102, 온보딩 API 는 KB-104). 외부 계약은 `:core:member` 가 공개하는 port·도메인 서비스 시그니처다.

## MemberIdentityResolver.resolve

```kotlin
fun resolve(identity: SocialIdentity): MemberResolution

data class MemberResolution(val member: Member, val isNewMember: Boolean)
```

| 입력 상황 | 결과 |
|-----------|------|
| (provider, providerUserId) 기존 신원 존재 | 기존 회원, isNewMember=false |
| 신원 없음 | 신규 회원 생성(빈 프로필·PENDING), isNewMember=true |
| 신원 없음 + email 이 기존 회원과 동일 | **그래도 신규 회원** (자동 통합 없음, FR-003) |
| email null/부재 | 정상 해소 (email 은 참고 정보) |
| 동시 최초 로그인 race 패자 | 중복 예외 → 재조회 → 기존 회원, isNewMember=false |
| 탈퇴 회원의 신원 | 미노출 → 신규 회원 생성 (US3 시나리오 2) |
| 가입 후 온보딩 미완료(PENDING) 회원 재로그인 | 기존 회원, isNewMember=false, **member.onboardingStatus=PENDING** (온보딩 유도 신호) |

`MemberResolution.member` 가 `onboardingStatus` 를 담으므로 라우팅 신호는 resolver 결과에서 바로 읽는다. 로그인(KB-102) 응답은 `isNewUser` 와 `onboardingStatus`(또는 `needsOnboarding`) 둘 다 노출한다.

## MemberRepository (port)

```kotlin
interface MemberRepository {
    fun findById(id: Long): Member?
    fun findByIdentity(provider: SocialProvider, providerUserId: String): Member?
    fun saveNew(member: Member): Member
    fun update(member: Member): Member
    fun withdraw(id: Long)
}
```

- 모든 조회는 탈퇴(soft delete) 회원을 반환하지 않는다 (FR-009).
- `saveNew`: (provider, providerUserId) 유니크 위반 시 `MemberException(DUPLICATE_SOCIAL_IDENTITY)`.
- `withdraw`: members soft delete + member_social_identities 물리 삭제 (R5). 부재/이미 탈퇴 시 `MemberException(MEMBER_NOT_FOUND)`.

## Member 도메인 계약

```kotlin
Member.signUp(identity): Member          // 빈 프로필 + PENDING
member.updateProfile(profile): Member    // 새 인스턴스 반환 (불변)
member.completeOnboarding(): Member      // PENDING→COMPLETED, 그 외 MemberException(ONBOARDING_ALREADY_COMPLETED)
```

## 소비자 (후속 이슈)

- KB-102 로그인: 토큰 검증 → `SocialIdentity` 구성 → `resolve(...)` → JWT(email 클레임 미포함) + isNewUser + onboardingStatus 응답(재방문 온보딩 유도).
- KB-104 온보딩: 입력 검증(카탈로그 81종·국가·10개국어) → `updateProfile` + `completeOnboarding` → `update(...)`.
- 탈퇴 API(KB-102 인증 후속): 현재 회원 확인 → `withdraw(id)`.
