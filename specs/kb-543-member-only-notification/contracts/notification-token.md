# Contract: PUT /api/notifications/tokens (X-API-Version 1.1+)

## 변경 요약

| 항목 | 변경 전(KB-465) | 변경 후(KB-543) |
|------|----------------|----------------|
| 인증 | 선택 — 게스트는 `Authorization` 없이, 회원은 Bearer | **필수** — Bearer access token. 없으면 **401**(JWT 필터, 다른 보호 경로와 같은 `AUTH-*` 코드) |
| `X-Installation-Id` 헤더 | 필수(36자 이하) | 동일 |
| 요청 본문 `token`·`platform`·`lang` | 필수 | 동일 |
| 요청 본문 `settings` | 선택(게스트 동의 on/off + 두 문구 버전) | **계약에서 제거**. 보내면 무시(200, 동의 원장 불변) |
| 동작 | 기기 upsert + 회원이면 연결 + 게스트면 동의 적용 | 기기 upsert + **항상 요청 회원에 연결**. 동의 원장을 건드리지 않는다 |
| 응답 | `200 BaseResponse<Unit>` | 동일 |

## 요청

```
PUT /api/notifications/tokens
X-API-Version: 1.1
X-Installation-Id: <uuid, ≤36>
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "token": "ExponentPushToken[...]", "platform": "ios", "lang": "en" }
```

## 응답

| 상태 | 조건 |
|------|------|
| 200 | 등록 또는 갱신 완료. `{"success":true,"payload":null}` — 같은 요청 반복 시 결과 동일(멱등) |
| 400 | `X-Installation-Id` 누락·공백·36자 초과, 본문 검증 실패(COMMON-002) |
| 401 | `Authorization` 없음·위조·만료 |
| 404 | `X-API-Version: 1.0` — 이 버전에는 존재하지 않는 API |

## Swagger 서술 변경(`NotificationTokenApi`)

- "게스트는 Authorization 없이…" 문단과 "게스트 K-Bap 소식 동의(settings)" 문단 삭제.
- "**회원 전용** — 로그인 후 호출한다. 기기는 요청 회원에 연결된다" 로 교체.
- `ApiResponse 401` 설명을 "Authorization 없음·위조·만료" 로, `400` 설명에서 `marketing=true 인데 버전 없음` 문구 삭제.

## 영향 받는 다른 엔드포인트(계약 불변, 내부 동작만 변경)

| 엔드포인트 | 내부 변화 |
|-----------|----------|
| `POST /api/auth/login`(1.1+, `X-Installation-Id` 선택) | 기기 연결만 수행. 게스트 동의 인수·철회 병합 **없음** |
| `POST /api/auth/logout` | 변화 없음(기기 연결 해제) |
| `PATCH /api/auth/withdraw` | 변화 없음(기기 연결 전체 해제 + 회원 열린 동의 닫기) |
| `PATCH /api/notifications/settings` | 변화 없음(`X-Installation-Id` 는 계속 "동의 받은 기기" 출처로 기록) |

## FE 전달 사항(SC-005)

- 토큰 등록은 **로그인 완료 후** 호출한다. 로그인 전 호출은 401 이며 앱 동작에 영향을 주지 않아야 한다.
- 요청 본문에서 `settings` 를 제거한다(남겨 보내도 무시).
- 게스트 광고 알림 동의 UI 가 있다면 제거한다 — 회원 동의는 설정 API 가 정본.
