# Contract: 업로드 완료 신고 API

## POST /api/v1/images/complete

인증 필수(`Authorization: Bearer <access>` — `@AuthMemberId`). 서명 URL 업로드(KB-145)를 마친 클라이언트가 호출한다.

### Request

```json
{
  "path": "scan/123/20260715-abc123.jpg",
  "contentType": "image/jpeg",
  "size": 1048576
}
```

| 필드 | 타입 | 필수 | 검증 |
|------|------|------|------|
| path | string | ✔ | blank 금지, `http(s)://` 시작 금지(전체 URL 거절 — path only), 길이 ≤512 |
| contentType | string | ✔ | 발급 시 신고했던 Content-Type |
| size | number | ✔ | 업로드한 파일 크기(bytes), 양수 |

### 처리

1. path 소유 확인 — 키의 회원 식별 prefix 가 인증 회원과 일치해야 한다.
2. 스토리지 HeadObject — 실제 Content-Type·크기 조회.
3. 판정: 실제 Content-Type 이 `image/*` 이고 신고값(contentType·size)과 일치하면 성공.
4. 성공 → `uploaded_image` 기록. 실패 → 오브젝트 DeleteObject 후 에러 응답.
5. 같은 path 재신고 → 이미 기록돼 있으면 재검증 없이 성공(멱등).

### Response 200

```json
{ "success": true, "payload": { "path": "scan/123/20260715-abc123.jpg" } }
```

### Errors (BaseResponse.fail)

| HTTP | code | 상황 | 오브젝트 |
|------|------|------|---------|
| 400 | IMAGE-001 | 실제 파일이 이미지가 아님(영상 등) | 삭제 |
| 400 | IMAGE-002 | 신고한 형식·크기가 실제와 불일치 | 삭제 |
| 400 | IMAGE-003 | 해당 경로에 오브젝트 없음 | — |
| 400 | COMMON-002 | 요청 형식 위반(blank·전체 URL 등) | — |
| 401 | AUTH-* | 미인증 | — |

Swagger(`ImageApi` 인터페이스): "비이미지·불일치 파일은 스토리지에서 삭제된다", "같은 path 재신고는 멱등" 명시.
