# Phase 1 Data Model: 회원 탈퇴 (KB-119)

## 스키마 변경: 없음

**Flyway 마이그레이션을 추가하지 않는다.** 탈퇴가 건드리는 컬럼이 이미 필요한 형태다.

| 컬럼 | 현재 정의 | 탈퇴 시 값 |
|---|---|---|
| `member.provider_uid` | `VARCHAR(255) NOT NULL`, `uk_member_provider_uid(provider, provider_uid)` | `'DELETED:{id}'` (회원마다 유일) |
| `member.status` (BaseEntity) | `ENUM('ACTIVE','DELETED')`, `@SQLRestriction("status = 'ACTIVE'")` | `DELETED` |
| `member.email` | `VARCHAR(255) NULL` | **그대로 유지** |

`member.provider`·`nickname`·`profile`(JSON)·`member_status` 도 그대로 둔다. `scan_history` 행도 그대로 둔다 — `member(id)` FK 는 소프트 삭제라 유효하게 남는다.

**`provider_uid` 치환이 필수인 이유**: 소프트 삭제는 행을 남기므로 유니크 인덱스에도 항목이 남는다. 치환하지 않으면 같은 소셜 계정 재가입의 INSERT 가 `uk_member_provider_uid` 에 걸려 409 가 된다. 조회(`findByIdentity`)가 ACTIVE 만 보더라도 INSERT 는 DB 레벨에서 막힌다.

## 도메인 (`:core:member`) — 변경 없음

- `Member`, `SocialIdentity`, `MemberProfile`: 무변경.
- `MemberRepository.withdraw(id: Long)`: **이미 존재하던 port**. 이번에 처음으로 프로덕션 호출자가 생긴다.
- `RefreshTokenStore`: 무변경(`save`/`consume`/`delete`).

## 영속 (`:infra:persistence`) — 변경 없음

`MemberJpaEntity.withdraw()` 와 `MemberRepositoryAdapter.withdraw(id)` 는 KB-117 구현 그대로 쓴다.

```kotlin
fun withdraw() {
    providerUid = deletedProviderUid(id)   // "DELETED:{id}"
    delete()                               // status = DELETED
}
```

## 애플리케이션 (`:application:client`) — 신규 포트 1개

```kotlin
// auth/SocialAccountDeleter.kt — 신규 port (도메인 타입만 노출)
interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}

// auth/FirebaseAccountDeleter.kt — 어댑터
//   getUserByProviderUid("google.com"|"apple.com", providerUserId).uid → deleteUser(uid)
//   USER_NOT_FOUND 는 성공으로 흡수(멱등)

// member/WithdrawUseCase.kt — findById → delete(social) → withdraw(id). @Transactional 없음.
```

`SocialTokenVerifier` 는 **무변경**(`verify(idToken): SocialIdentity`). `AuthErrorCode` 에 `SOCIAL_ACCOUNT_DELETE_FAILED(500)` 만 추가. `RefreshUseCase` 에 `MemberRepository` 주입.

## 상태 전이

```
ACTIVE 회원
   │  PATCH /auth/withdraw (본문 없음, access token 만)
   │    ├─ 회원 없음 ─────────────────────► 400, 상태 변화 없음
   │    ├─ 인증 제공자 삭제 실패 ─────────► 500 + ERROR 로그, 상태 변화 없음
   │    └─ 인증 제공자 삭제 성공
   ▼
DELETED 회원 (status=DELETED, provider_uid='DELETED:{id}', email·nickname·profile 유지)
   │
   ├─ 기존 access token → 회원 조회 API 400 (findById 가 못 찾음)
   ├─ 기존 refresh token → 재발급 401 (RefreshUseCase 의 회원 존재 확인)
   └─ 같은 소셜 계정 재로그인 → 신규 회원 (새 id, 온보딩 미완료)
```
