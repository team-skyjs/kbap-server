# API Contract Changes: KB-154

신규 엔드포인트 없음. 기존 필드의 **값 의미**만 변경(필드명·구조 불변).

## 입력 변경 (파괴적 — 클라이언트 계약 변경, 사전 합의된 티켓 범위)

### `POST /api/v1/members/onboarding` · `PATCH /api/v1/members/me`

| 필드 | 이전 | 이후 |
|---|---|---|
| `profileImageUrl` | https 전체 URL(허용 호스트 검증) | **경로(objectKey)** — presigned 발급 응답의 `objectKey` 그대로. `http(s)://` 시작 값은 400 `MEMBER-008` |

- 3분법 불변: 미전송=유지 · 값=검증 후 교체 · 빈 문자열=제거
- `MEMBER-008` 코드 유지, 메시지 문구만 경로 기준으로 변경

## 출력 변경

### `GET /api/v1/members/me` — `profileImageUrl`

- 이전: 저장된 전체 URL 그대로
- 이후: 저장 경로에 CDN 베이스가 조합된 **완전한 URL**(예: `https://cdn.example.com/profile-image/2026/07/18/1/uuid.jpg`). 미등록이면 null. 레거시 절대 URL 저장 행은 그대로 반환

### `GET /api/v1/foods/**` (상세·목록·검색) · `GET /api/v1/home` — `imageRef`

- 이전: `image_ref` 저장값 그대로
- 이후: CDN 베이스가 조합된 완전한 URL. 없으면 null

## Swagger 문서

- `MemberApi` 온보딩·프로필 수정 설명과 예시를 경로 입력(`"profileImageUrl": "profile-image/2026/07/18/1/uuid.jpg"`)으로 갱신
