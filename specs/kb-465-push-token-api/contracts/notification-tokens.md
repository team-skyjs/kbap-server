# Contract: PUT /api/notifications/tokens + 인증 API 헤더 확장

모든 응답은 `BaseResponse<T>` 봉투(`success`·`payload`·`message`·`code`). **이 기능의 계약은 전부 `X-API-Version: 1.1` 이상에서만 제공된다** — 토큰 API 는 1.0 에 존재하지 않고(400), 인증 API 의 1.0 계약은 종전 그대로다(research R11). 이 기능은 새 ErrorCode 를 만들지 않는다. 구현 클래스는 `api.notification` 의 `NotificationToken*`, URL 은 `/api/notifications/tokens`(Jira 초안 `/api/push/tokens` 대체).

## PUT /api/notifications/tokens — 기기 토큰 등록·갱신

기기 설치 식별자 기준 upsert. 게스트·회원 공통 진입점. 멱등 — 같은 요청을 반복해도 결과 상태가 같다.

### 요청

| 위치 | 이름 | 필수 | 제약 | 설명 |
|---|---|---|---|---|
| Header | `X-Installation-Id` | **필수** | 공백 불가, 36자 이하 | 앱 설치 UUID. 기기 기록의 유일 키 |
| Header | `X-API-Version` | 필수 | **`1.1` 이상** | `1.0`·누락·미지원 → 400 COMMON-002 |
| Header | `Authorization` | 선택 | `Bearer {accessToken}` | 있으면 회원 요청 — 기기를 그 회원에 연결. 위조·만료면 401 |
| Body | `token` | 필수 | 공백 불가, 255자 이하 | Expo push token |
| Body | `platform` | 필수 | `ios` \| `android` (대소문자 무관) | |
| Body | `lang` | 필수 | 공백 불가, 10자 이하 | 기기 언어. 검증·정규화 없이 저장 |
| Body | `settings` | 선택 | 객체 | 게스트 광고성 동의. **회원 요청에서는 무시**(검증은 동일 적용) |
| Body | `settings.marketing` | settings 가 있으면 필수 | boolean | 광고성 수신 동의 on/off |
| Body | `settings.marketingConsentVersion` | `marketing=true` 면 필수 | 양의 정수 | 동의 문구 버전 |

```json
{
  "token": "ExponentPushToken[xxxxxxxxxxxxxxxxxxxxxx]",
  "platform": "ios",
  "lang": "en",
  "settings": { "marketing": true, "marketingConsentVersion": 1 }
}
```

### 응답

| 상태 | 봉투 | 조건 |
|---|---|---|
| 200 | `{ "success": true, "payload": null }` | 생성·갱신 모두. 페이로드 없음(`Unit`) |
| 400 | `code: COMMON-002` | `X-API-Version` 이 `1.0`·누락·미지원, `X-Installation-Id` 누락·공백·36자 초과, `token`/`platform`/`lang` 검증 실패, `marketing=true` 인데 버전 없음, 버전이 양의 정수 아님, 본문 해석 불가 |
| 401 | `code: AUTH-*` (기존) | `Authorization` 이 있으나 위조·만료 |

### 서버 동작 (요청 조합별)

| 인증 | 기기 존재 | 기기 처리 | 동의 처리 |
|---|---|---|---|
| 게스트 | 없음 | `register`(memberId=null) | `settings` 있으면 원장 반영 |
| 게스트 | 있음 | `renew`(토큰·플랫폼·언어 덮어쓰기, 무효 해제). 기존 회원 연결은 유지 | 동일 |
| 회원 m | 없음 | `register`(memberId=m) | 무시 |
| 회원 m | 있음 | `renew` + `linkMember(m)` | 무시 |

동의 원장 반영 규칙은 [data-model.md](../data-model.md) 사건 표.

### swagger 문서 (NotificationTokenApi)

`@Tag(name = "푸시")`, `@Operation(summary = "푸시 토큰 등록·갱신 — X-API-Version 1.1 이상")`, 매핑은 `@PutMapping("/tokens", version = "1.1+")` 라 springdoc `X-API-Version 1.1` 그룹 문서에만 실린다. 헤더는 `@Parameter(name = "X-Installation-Id", in = ParameterIn.HEADER, required = true)`. `@SecurityRequirement(bearerAuth)` 는 선택 인증이므로 description 에 "게스트는 헤더 없이, 회원은 Bearer 로" 를 적고 애너테이션은 달지 않는다(달면 문서상 필수로 보인다). `@AuthMemberIdOrNull` 은 `OpenApiConfig` 가 숨긴다.

## 인증 API — `1.1+` 매핑 추가 (경로·본문·응답 불변, 1.0 계약 불변)

같은 경로에 `version = "1.1+"` 매핑을 나란히 두고, 1.1+ 핸들러만 기기 처리를 한다. 1.0 요청(무버전 매핑)은 종전 핸들러가 받아 기기·동의를 전혀 건드리지 않는다.

| 엔드포인트 | 1.0 (기존, 불변) | 1.1+ (추가) |
|---|---|---|
| `POST /api/auth/login` | 종전 로그인만 | `X-Installation-Id`(선택) 가 있고 기기가 등록돼 있으면 회원 연결 + 게스트 동의 인수/철회. 없거나 미등록이면 로그인만 |
| `POST /api/auth/logout` | 종전 refresh 폐기만 | `X-Installation-Id`(선택) 가 있고 기기가 등록돼 있으면 회원 연결 해제. 동의 원장 불변 |
| `PATCH /api/auth/withdraw` | 종전 탈퇴만(기기·동의 무처리) | access 토큰의 회원 기준으로 모든 기기 연결 해제 + 열린 동의 전부 철회 |

기기 처리는 인증 결과에 영향을 주지 않는다 — 헤더가 없어도 기존 응답·상태 코드 그대로다. `AuthApi` 에 1.1+ 메서드 3개(`loginWithDevice`·`logoutWithDevice`·`withdrawWithDevices`)의 문서를 추가하고, login·logout 에는 `@Parameter(name = "X-Installation-Id", in = HEADER, required = false, description = ...)` 를 단다. 기존 1.0 메서드 문서는 손대지 않는다.

## 보호 경로 등록

`/api/notifications/tokens` 는 `WebConfig` 의 JWT 보호 경로에 **등록하지 않는다**(게스트 허용 — `/api/home` 선례, research R2). `@AuthMemberIdOrNull` 리졸버가 `Authorization` 을 직접 해석한다.
