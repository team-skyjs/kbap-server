# Contract: 업로드 URL 발급

## `POST /api/v1/images/upload-url`

인증된 사용자에게 이미지 업로드용 presigned PUT URL 과 저장·표시용 안정 공개 URL 을 발급한다.

- **인증**: JWT 필수(`Authorization: Bearer <access>`). 없거나 만료 → 401(`AUTH-003`/`AUTH-004`).
- **응답 봉투**: `ResponseEntity<BaseResponse<UploadUrlResponse>>`.

### Request Body

```json
{
  "purpose": "MENU_SCAN",
  "contentType": "image/jpeg",
  "contentLength": 384512
}
```

| 필드 | 타입 | 필수 | 검증 |
|------|------|------|------|
| `purpose` | string(enum) | ✓ | `UploadPurpose` 값. 미지원 → `UPLOAD-002` (400) |
| `contentType` | string | ✓ | 허용 목록(image/jpeg·png·webp). 위반 → `UPLOAD-001` (400) |
| `contentLength` | number(long) | ✓ | > 0, ≤ maxBytes. 초과 → `UPLOAD-003` (400) |

### 200 성공

```json
{
  "success": true,
  "payload": {
    "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/menu-scan/2026/07/15/1024/3f2a...c9.jpg?X-Amz-Algorithm=...&X-Amz-Signature=...",
    "method": "PUT",
    "requiredHeaders": {
      "Content-Type": "image/jpeg",
      "Content-Length": "384512"
    },
    "publicUrl": "https://cdn.dev.kbap.app/menu-scan/2026/07/15/1024/3f2a...c9.jpg",
    "objectKey": "menu-scan/2026/07/15/1024/3f2a...c9.jpg",
    "expiresAt": "2026-07-15T05:10:00Z"
  },
  "message": null,
  "code": null
}
```

### 클라이언트 업로드 절차

1. 이 API 로 `uploadUrl`·`requiredHeaders` 획득.
2. `PUT uploadUrl` — body=이미지 바이트, **`requiredHeaders` 를 그대로 전송**(Content-Type·Content-Length 불일치 시 S3 403). 서버(백엔드) 경유 없음.
3. 업로드 성공 후 `publicUrl`(또는 `objectKey`)을 백엔드 소비 API(프로필 수정·스캔 등)에 전달 → 백엔드가 DB 저장. `publicUrl` 은 만료 없이 표시·LLM fetch 에 재사용.

### 에러

| 상황 | HTTP | code |
|------|------|------|
| 미지원 Content-Type | 400 | `UPLOAD-001` |
| 미지원 purpose | 400 | `UPLOAD-002` |
| contentLength 초과 | 400 | `UPLOAD-003` |
| 요청 검증 실패(필수 누락·음수) | 400 | `COMMON-002` |
| 토큰 부재·위조·만료 | 401 | `AUTH-003`/`AUTH-004` |
| 스토리지 미구성(운영 오류) | 500 | `COMMON-003` |

실패 봉투: `{ "success": false, "payload": null, "message": "...", "code": "UPLOAD-001" }`.

### 정책 (프로필 설정 `kbap.storage.*` / `kbap.upload.*`)

- `uploadTtl`: 업로드 URL 만료(예: 5분).
- `maxBytes`: 크기 상한(예: 10MB).
- `allowedContentTypes`: image/jpeg·png·webp.
- `publicBaseUrl`: 공개/CDN 베이스.
- **로컬·테스트**: 스토리지 미구성 → 발급 500(운영), 컨트롤러 테스트는 페이크 port 로 200 검증. 실 S3 는 dev/prod 프로필만.
