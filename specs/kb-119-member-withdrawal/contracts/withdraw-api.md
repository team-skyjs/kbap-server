# API Contract: 회원 탈퇴

## `PATCH /api/v1/members/me/withdraw`

로그인한 회원이 자기 계정을 탈퇴한다. 인증 제공자(Firebase) 사용자 기록을 삭제한 뒤 회원 행을 소프트 삭제한다.

### Request

```
PATCH /api/v1/members/me/withdraw
Authorization: Bearer {accessToken}
```

**요청 본문 없음.** 접근 토큰이 대상 회원을 결정한다(`@AuthMemberId`). 소셜 ID 토큰을 받지 않으므로 재인증도 없다.

### Response

성공 — `200 OK`

```json
{ "success": true, "payload": null, "message": null }
```

실패 — `BaseResponse.fail(message)`

| status | 조건 | message |
|---|---|---|
| 401 | 접근 토큰 부재·위조·만료 | 유효하지 않은 인증 토큰입니다 / 만료된 인증 토큰입니다 |
| 404 | 회원 없음(이미 탈퇴 포함) | 해당 회원을 찾을 수 없습니다 |
| 500 | 인증 제공자 사용자 기록 삭제 실패 | 소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요 |

**어떤 실패에서도 회원 데이터는 변경되지 않는다**(부분 탈퇴 없음).

### 처리 순서

1. `@AuthMemberId` 로 회원 해석 → `memberRepository.findById(memberId)`, 없으면 404.
2. `socialAccountDeleter.delete(member.identity.provider, member.identity.providerUserId)`
   — Firebase 어댑터가 `getUserByProviderUid("google.com"|"apple.com", providerUserId)` 로 local uid 를 조회해 `deleteUser(uid)` 를 호출한다. 대상이 이미 없으면(`USER_NOT_FOUND`) 성공 취급.
   실패 시 ERROR 로그(`memberId`·`provider`·`providerUserId`·사유) 후 500.
3. `memberRepository.withdraw(memberId)` — 소프트 삭제 + `provider_uid` 를 `DELETED:{id}` 로 치환. 이메일·닉네임·프로필은 그대로 둔다.

### 탈퇴 이후 다른 API 의 동작

| 호출 | 결과 |
|---|---|
| `GET /api/v1/members/me/profile` (기존 access token) | 404 — 해당 회원을 찾을 수 없습니다 |
| `POST /api/v1/auth/refresh` (기존 refresh token, 어느 기기든) | 401 — 유효하지 않은 갱신 토큰입니다 |
| `POST /api/v1/auth/login` (같은 소셜 계정) | 200 — `newMember: true`, 새 회원, 온보딩 미완료 |
