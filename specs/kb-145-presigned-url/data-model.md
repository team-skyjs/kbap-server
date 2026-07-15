# Data Model: 이미지 업로드용 presigned URL 발급 API (KB-145)

**영속 엔티티 없음.** DB 테이블·JPA 엔티티·Flyway 마이그레이션을 만들지 않는다 — 발급은 무상태 로컬 서명이고, 업로드된 이미지 URL 의 저장은 각 소비 기능(프로필·리뷰·스캔)의 책임이다. 아래는 코드 값 타입(도메인 모델 아님)이다.

## 값 타입 (`:application`)

### UploadPurpose (enum)
업로드 창구를 구분하는 용도. 객체 키 prefix 를 결정.

| 값 | prefix | 상태 |
|----|--------|------|
| `MENU_SCAN` | `menu-scan` | 초기 지원(첫 소비자) |
| `REVIEW` | `review` | placeholder — 리뷰 태스크에서 활성(초기엔 미지원 처리 가능) |

- 미지원 값 요청 → `UPLOAD-002`(400). (요청 DTO 에서 enum 파싱 실패도 400 으로 수렴.)

### PresignUploadCommand (서비스 입력)
| 필드 | 타입 | 설명 |
|------|------|------|
| `memberId` | `Long` | 인증 회원(객체 키 소유 세그먼트) |
| `purpose` | `UploadPurpose` | 용도 |
| `contentType` | `String` | 업로드 이미지 MIME(예: image/jpeg) |
| `contentLength` | `Long` | 클라이언트 신고 바이트 수(정확값) |

### PresignedUpload (서비스 결과)
| 필드 | 타입 | 설명 |
|------|------|------|
| `uploadUrl` | `String` | 업로드용 presigned PUT URL(만료) |
| `requiredHeaders` | `Map<String,String>` | PUT 시 반드시 실을 서명 헤더(Content-Type, Content-Length) |
| `publicUrl` | `String` | 업로드 후 저장·표시용 안정 공개 URL(만료 없음) |
| `objectKey` | `String` | S3 객체 키(참조 값) |
| `expiresAt` | `Instant` | 업로드 URL 만료 시각 |

### ImageUploadProperties (정책값 — 설정 바인딩)
| 필드 | 타입 | 예시/기본 |
|------|------|-----------|
| `allowedContentTypes` | `Set<String>` | `image/jpeg`, `image/png`, `image/webp` |
| `maxBytes` | `Long` | 예: 10 * 1024 * 1024 (10MB) |
| `uploadTtl` | `Duration` | 예: PT5M |
| `publicBaseUrl` | `String` | CDN/공개 베이스(예: https://cdn.dev.kbap.app) |
| `purposePrefixes` | `Map<UploadPurpose,String>` | 용도→prefix |

## 검증 규칙 (ImageUploadApplicationService)

1. `purpose` 미지원 → `UPLOAD-002`.
2. `contentType ∉ allowedContentTypes` → `UPLOAD-001`.
3. `contentLength > maxBytes` → `UPLOAD-003`.
4. 통과 시 객체 키 생성 `{prefix}/{yyyy}/{MM}/{dd}/{memberId}/{UUID}.{ext}` → `PresignUploadCommand` 구성 → `PresignedUploadPort.issue(...)` 위임 → 결과 반환.

`ext`(확장자) 는 Content-Type 매핑(image/jpeg→jpg, image/png→png, image/webp→webp). 매핑에 없는 타입은 규칙 2 에서 이미 거절.

## seam 인터페이스 (`:application`)

```
PresignedUploadPort {
    fun issue(command: PresignUploadCommand, key: String, ttl: Duration): PresignedUpload
}
```
- 실 구현 `S3PresignedUploadPort`(`:infra:storage`) — S3Presigner 로 PUT presign + `publicBaseUrl+key` 조립.
- fallback `UnavailablePresignedUploadPort`(`:application`) — 미구성 시 호출하면 `BusinessException(INTERNAL_SERVER_ERROR)`.

## 경계 DTO (`:app:api`)

- `UploadUrlRequest` `{ purpose: String, contentType: String, contentLength: Long }`(+ `@field:` validation: NotBlank/Positive). 도메인 값 타입으로 매핑.
- `UploadUrlResponse.from(PresignedUpload)` `{ uploadUrl, method, requiredHeaders, publicUrl, objectKey, expiresAt }`.

**상태 전이 없음**(무상태 발급).
