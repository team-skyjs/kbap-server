# Auth API Contract (KB-118)

모든 응답은 `ResponseEntity<BaseResponse<T>>` 봉투(규약). 인증 실패는 401 + `success=false`.
토큰 전달 = **응답 본문**(사용자 결정 개정 — 클라이언트가 모바일 앱이라 쿠키 자동 전송이 없음).
클라이언트는 안전 저장소(Keychain/Keystore)에 보관하고 매 요청 `Authorization: Bearer {accessToken}` 로 보낸다
("Bearer " 접두사는 클라이언트가 붙인다).

## POST /api/v1/auth/login

응답에 memberId 를 담지 않는다 — 회원 식별은 항상 access 토큰(sub)에서 서버가 유도하고,
클라이언트가 자기 id 를 보내는 API 는 만들지 않는다(IDOR 방지). 필요해지면 필드 추가는 하위호환이다.

**Request**: `{ "idToken": "<Firebase ID token>" }`

**Response 200**

```json
{ "success": true, "payload": { "newMember": true, "accessToken": "<jwt>", "refreshToken": "<jwt>" } }

```

**오류**

| 상황 | 상태 | message |
|------|------|---------|
| 서명 위조·만료·aud/iss 불일치 | 401 | 유효하지 않은 소셜 인증 토큰입니다 |
| 미지원 provider (구글·애플 외) | 401 | 지원하지 않는 소셜 로그인 제공자입니다 |
| 정지 회원 | 409 | (기존 DUPLICATE_SOCIAL_IDENTITY 메시지 — R6 알려진 한계) |
| idToken 누락/blank | 400 | validation 메시지 |

## POST /api/v1/auth/refresh

rotation — access·refresh 둘 다 재발급, 이전 refresh 즉시 폐기·수명 연장. 클라이언트는 두 토큰 모두 갱신 저장.

**Request**: `{ "refreshToken": "<jwt>" }`

**Response 200**: `{ "success": true, "payload": { "accessToken": "<새 jwt>", "refreshToken": "<새 jwt>" } }`

**오류** (400 = refreshToken 누락, 그 외 401):

| 상황 | 코드 | 클라이언트 행동 |
|------|------|----------------|
| 서명 조작·형식 불량·Redis 미존재(로그아웃·회전된 구 토큰 재사용·위조) | INVALID_REFRESH_TOKEN | 저장 토큰 삭제 후 재로그인 |
| **refresh 만료** | EXPIRED_REFRESH_TOKEN — 서버 세션도 폐기(강제 로그아웃) | 저장 토큰 삭제 후 재로그인 |

## POST /api/v1/auth/logout

서버 refresh 세션 폐기. 멱등 — body 없거나 이미 폐기된 토큰이어도 200. 클라이언트는 저장 토큰 2개 삭제.

**Request**: `{ "refreshToken": "<jwt>" }` (optional)

**Response 200**: `{ "success": true }`
