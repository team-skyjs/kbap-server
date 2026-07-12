# API Contract: 프로필 부분 수정

## `PATCH /api/v1/members/me/profile`

로그인한 회원이 프로필 항목 중 **바꾸고 싶은 것만** 담아 보낸다. 담지 않은 항목은 기존 값을 유지한다.

### Request

```
PATCH /api/v1/members/me/profile
Authorization: Bearer {accessToken}
Content-Type: application/json
```

| 필드 | 타입 | 필수 | 미전송 시 | 설명 |
|---|---|---|---|---|
| `nickname` | String | ✕ | 기존 값 유지 | 앞뒤 공백 제거 후 저장. 공백뿐이면 400 |
| `avoidanceSubstanceCodes` | String[] | ✕ | 기존 값 유지 | **`[]` 는 "전부 해제"** — 미전송과 다르다. 중복 코드는 제거 |
| `countryCode` | String | ✕ | 기존 값 유지 | 지정 목록의 국가 코드 |
| `appLanguage` | String | ✕ | 기존 값 유지 | 지원 언어 코드(정확 일치) |

**필드에 명시적으로 `null` 을 보내는 것은 미전송과 같다**(유지). 개별 항목을 비우는 의미는 없다.

#### 예시

닉네임·국가·언어 화면 — 기피 성분은 건드리지 않는다:
```json
{ "nickname": "길동이", "countryCode": "KR", "appLanguage": "ko" }
```

기피 성분 화면 — 나머지는 건드리지 않는다:
```json
{ "avoidanceSubstanceCodes": ["EGG", "MILK"] }
```

기피 성분 전부 해제:
```json
{ "avoidanceSubstanceCodes": [] }
```

아무것도 바꾸지 않음(허용):
```json
{}
```

### Response

성공 — `200 OK`

```json
{ "success": true, "payload": null, "message": null }
```

실패 — `BaseResponse.fail(message)`. **어떤 실패에서도 프로필은 하나도 바뀌지 않는다**(부분 저장 없음).

| status | 조건 | message |
|---|---|---|
| 400 | 전달된 닉네임이 공백뿐 | 닉네임은 비어 있을 수 없습니다 |
| 400 | 전달된 기피 성분 코드가 카탈로그에 없음 | 지원하지 않는 기피 성분 코드입니다 |
| 400 | 전달된 국가 코드가 지정 목록에 없음 | 지원하지 않는 국가 코드입니다 |
| 400 | 전달된 언어 코드가 지원 목록에 없음 | 지원하지 않는 언어입니다 |
| 400 | 회원 없음(탈퇴 포함) | 해당 회원을 찾을 수 없습니다 |
| 401 | 미인증(토큰 부재·위조·만료) | 유효하지 않은 인증 토큰입니다 |

**미전송 필드 때문에 400 이 발생하지 않는다.**

### 변경 전후

| 요청 | 변경 전 | 변경 후 |
|---|---|---|
| `{"nickname":"A","countryCode":"KR","appLanguage":"ko"}` | 기피 성분이 **조용히 전부 삭제**됨 | 기피 성분 유지 |
| `{"avoidanceSubstanceCodes":["EGG"]}` | **400** (닉네임·국가·언어 누락) | 기피 성분만 교체 |
| 네 필드 모두 전송 | 전부 교체 | 전부 교체 (동일 — 기존 클라이언트 무영향) |

## `POST /api/v1/members/me/onboarding` — 변경 없음

전 필드 필수를 그대로 유지한다. 요청 형식·검증·응답 모두 동일하다.
