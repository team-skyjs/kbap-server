# API Contracts: kb-229-scan-lang-param

응답 봉투는 전 API 공통 `BaseResponse<T>`(success/payload/message/code), 경로는 `/api/v1` 규약을 따른다.

## 변경 1 — POST /api/v1/scans (스캔: lang 쿼리 파라미터 추가)

### Request

```
POST /api/v1/scans?lang={code}
Authorization: Bearer {accessToken}
Content-Type: application/json
```

- **`lang`** (query, **필수 — 신규**): 표시명 언어 코드. 지원: `ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es`.
  - 누락·빈 값·공백 → **400** (`COMMON-002` 계열 검증 실패 — 기존 GlobalExceptionHandler 경로)
  - 지원 목록에 없는 값(`fr`·`EN`·`ko-KR` 등) → 거절하지 않고 **`en` 으로 응답** (정확 일치 매칭, 정규화 없음)
- Body(`ScanRequest`: imagePath + items)는 **변경 없음**.

### Response (200 — 변경점만)

- `payload.results[].name`: 매칭된 메뉴는 **요청 `lang` 의 번역**으로 내려간다(번역 부재 시 `ko` 폴백). 회원 프로필 설정은 더 이상 관여하지 않는다.
- `matched=false`(신규·조사 대기) 메뉴는 번역본이 없으므로 `name`·`koreanName` 모두 비전 LLM 이 정제한 표준 한국어명이 내려간다(같은 값). 그 외 필드는 기존과 동일.

### 제거되는 동작

- 회원 프로필 `appLanguage` 로 응답 언어 결정(미설정 시 KO) — 완전 제거.

## 변경 2 — 회원 API 계약 축소 (appLanguage 소멸)

### POST /api/v1/members/me/onboarding

- 요청 body 에서 `appLanguage` 필드 **제거**(기존 필수 → 소멸).
- 구버전 앱이 `appLanguage` 를 포함해 보내면 **무시하고 정상 처리**한다(unknown key 관용 — 400 아님).

### PATCH /api/v1/members/me/profile

- 요청 body 에서 `appLanguage` 필드 **제거**. 포함해 보내면 무시하고 정상 처리.

### GET /api/v1/members/me/profile

- 응답 payload 에서 `appLanguage` 필드 **제거**.

## Swagger 갱신

- `ScanApi`: `lang` 쿼리 파라미터 문서화(지원 코드 목록·en 폴백 — HomeRequest 와 동일 서술).
- `MemberApi`: 온보딩·수정·조회 예시 JSON 에서 `appLanguage` 제거.
