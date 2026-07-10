# Auth API Contract (KB-118)

모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투(규약). 인증 실패는 401 + `success=false`.

## POST /api/v1/auth/login

Firebase ID 토큰으로 로그인/가입. 성공 시 자체 토큰 2종을 쿠키로 발급.

**Request**

```json
{ "idToken": "<Firebase ID token>" }
```

**Response 200**

```json
{ "success": true, "payload": { "memberId": 1, "newMember": true }, "message": null }
```

Set-Cookie:

```
access_token=<jwt>; Path=/; Max-Age=1800; HttpOnly; SameSite=Lax[; Secure]
refresh_token=<jwt>; Path=/api/v1/auth; Max-Age=1209600; HttpOnly; SameSite=Lax[; Secure]
```

**오류**

| 상황 | 상태 | message |
|------|------|---------|
| 서명 위조·만료·aud/iss 불일치 | 401 | 유효하지 않은 소셜 인증 토큰입니다 |
| 미지원 provider (구글·애플 외) | 401 | 지원하지 않는 소셜 로그인 제공자입니다 |
| 정지 회원 | 409 | (기존 DUPLICATE_SOCIAL_IDENTITY 메시지 — R6 알려진 한계) |
| idToken 누락/blank | 400 | validation 메시지 |

## POST /api/v1/auth/refresh

refresh 쿠키로 **access·refresh 둘 다 재발급**(rotation). 이전 refresh 는 즉시 폐기되고 refresh 유효기간이 연장된다.

**Request**: body 없음, Cookie `refresh_token` 필요.

**Response 200**: `{ "success": true }` + Set-Cookie 2건 — `access_token`(신규)·`refresh_token`(신규, Max-Age 14d 재설정).

**오류** (전부 401, BaseResponse fail):

| 상황 | 코드 | message | 부가 동작 |
|------|------|---------|-----------|
| 쿠키 부재·서명 조작·형식 불량 | INVALID_REFRESH_TOKEN | 유효하지 않은 갱신 토큰입니다 | 쿠키 2종 만료 |
| Redis 에 jti 없음 — 로그아웃·rotation 된 구 토큰 재사용·위조 | INVALID_REFRESH_TOKEN | 유효하지 않은 갱신 토큰입니다 | 쿠키 2종 만료 |
| **refresh 만료** | EXPIRED_REFRESH_TOKEN | 만료된 갱신 토큰입니다 | **강제 로그아웃** — Redis jti 삭제 + 쿠키 2종 만료, 재로그인 필수 |

## POST /api/v1/auth/logout

서버 저장 refresh 세션 폐기 + 쿠키 만료. 멱등(이미 폐기됐어도 200).

**Request**: body 없음, Cookie `refresh_token`(없어도 200 — 쿠키 만료만 수행).

**Response 200**: `{ "success": true }` + Set-Cookie 두 쿠키 `Max-Age=0`.
