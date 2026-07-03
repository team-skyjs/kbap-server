# Contract: 음식 상세조회 — 언어 코드 검증

**Endpoint**: `GET /api/v1/foods/detail` (기존)

기존 계약은 유지하고, **`lang` 파라미터의 미지원 코드 처리 동작만 변경**한다.

## Request

| Param | Type | Required | 설명 |
|-------|------|----------|------|
| `menuName` | String | ✅ | 한국어 메뉴명 (기존) |
| `lang` | String | ❌ | 응답 언어 코드. 미지정 시 `ko`. **미지원 코드는 400 에러**(변경) |

## Response — 응답 봉투 `BaseResponse<T>`

### 성공 (기존 유지)
- 지원 언어(`ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es`) 또는 `lang` 미지정/빈/공백
- **200 OK** + `{ "success": true, "payload": { ... }, "message": null }`
- 지원 언어이나 번역 부재 시: 200 + 한국어 폴백 payload (기존 정책 유지)

### 실패 — 미지원 언어 코드 (신규 동작)
- 조건: `lang` 이 비어있지 않고 지원 목록과 **정확히 일치하지 않음** (예: `fr`, `EN`, `ko-KR`, `xx`)
- **400 Bad Request** + `{ "success": false, "payload": null, "message": "<미지원 코드 안내 + 지원 목록>" }`
- `message` 는 지원 언어 10종(`ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es`)을 포함해야 한다.

### 실패 — 기존 (유지)
- `menuName` 누락/blank → 400 `"menuName은 필수입니다"`
- 미수록 메뉴 → 400 `"해당 음식 정보 없음"`

## 계약 검증 시나리오 (MockMvc)

1. `menuName=된장찌개&lang=ja` → 200, `payload.name` = 일본어명 (기존)
2. `menuName=된장찌개&lang=xx` → **400, `success=false`, `message` 에 지원 목록 포함** (변경 — 기존 200 폴백 폐지)
3. `menuName=된장찌개&lang=fr` → 400, 지원 목록 메시지 (신규)
4. `menuName=된장찌개` (lang 생략) → 200, 한국어명 (기존 유지)
5. `menuName=된장찌개&lang=` (빈 값) → 200, 한국어명 (기존 유지)
