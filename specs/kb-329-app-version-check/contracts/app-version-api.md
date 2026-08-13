# API Contract: 앱 버전 정보

모든 응답은 `BaseResponse` 봉투(`success`·`payload`·`message`·`code`)를 따른다.

## 1. 공개 버전 정보 조회

```
GET /api/app-version
인증: 없음 (JWT 필터 미등록 — 로그인 전 호출 가능)
버저닝: 기본 (X-API-Version 분기 없음)
```

**200 OK**

```json
{
  "success": true,
  "payload": {
    "minSupportedVersion": "1.0.0",
    "latestVersion": "1.0.1",
    "storeUrls": {
      "ios": "https://apps.apple.com/...",
      "aos": null
    }
  },
  "message": null,
  "code": null
}
```

- `storeUrls.ios`/`storeUrls.aos`: 미배포·미설정 플랫폼은 `null` (필드 누락·빈 문자열 금지).
- 버전 비교·강제 업데이트 판단은 클라이언트 책임 — 서버는 클라이언트의 현재 버전을 받지 않는다.

## 2. 관리자 버전 정보 조회

```
GET /api/admin/app-version
인증: JWT + ADMIN 롤 (기존 /api/admin/** 보호 체계)
```

**200 OK** — payload 는 공개 조회와 동일 형태.

**실패**: 토큰 없음/무효 401(기존 AUTH 코드), ADMIN 롤 아님 → `AUTH-008` 403.

## 3. 관리자 버전 정보 갱신

```
PUT /api/admin/app-version
인증: JWT + ADMIN 롤
```

**Request Body**

```json
{
  "minSupportedVersion": "1.0.0",
  "latestVersion": "1.0.2",
  "iosStoreUrl": "https://apps.apple.com/...",
  "aosStoreUrl": null
}
```

- `minSupportedVersion`·`latestVersion`: 필수, `major.minor.patch` 형식(정규식 `^\d+\.\d+\.\d+$`). 위반 시 `COMMON-002` 400.
- `iosStoreUrl`·`aosStoreUrl`: 선택(null 허용), 최대 512자.
- 전체 값 치환(PUT) — 부분 수정 아님.

**200 OK** — payload 는 갱신된 값으로 공개 조회와 동일 형태. 갱신은 즉시 공개 조회에 반영된다.

**실패**: 형식 위반 `COMMON-002` 400 · 비관리자 `AUTH-008` 403 · 무인증 401.
