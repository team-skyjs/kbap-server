# API Contract Changes: 프로필 사진 필수화 (KB-188)

필드명·엔드포인트·응답 구조 무변경 — 요청 값 계약만 바뀐다. 모든 응답은 `BaseResponse<T>` 봉투.

## POST /api/v1/members/me/onboarding (변경)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `profileImageUrl` | 선택 (`String?`, 미전송=미설정) | **필수** (`String`) — 미설정 회원도 클라이언트가 기본 이미지 경로 `/images/default/profile/profile-default-512.png` 를 명시 전송 |
| 미전송·null | 200 (null 저장) | **400 `COMMON-002`** |
| 빈 문자열·공백 | 200 (null 저장) | **400 `MEMBER-008`** |
| 전체 URL(`http(s)://`)·512자 초과 | 400 `MEMBER-008` | 동일 (유지) |

## PATCH(수정 API) /api/v1/members/me/profile (변경)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| `profileImageUrl` 미전송/null | 유지 | 동일 (유지 — KB-124 부분 수정 규약 불변) |
| 빈 문자열·공백 | **제거**(null 저장) | **400 `MEMBER-008`** — 제거 센티널 폐기. 기본 이미지로 되돌리려면 기본 이미지 경로를 명시 전송 |
| 유효 경로 | 검증 후 교체 | 동일 (유지) |

## 조회 응답 (무변경)

- `profileImageUrl` 출력은 저장 경로 + CDN 도메인 조합의 완전한 URL(KB-154 구조 그대로). 기본 이미지 경로도 동일하게 조합된다.
- 온보딩 전 회원의 조회 응답 `profileImageUrl` 은 여전히 null 일 수 있다(온보딩 전 상태 — 계약 밖).

## Swagger (MemberApi) 반영 사항

- 온보딩: `profileImageUrl` 을 선택 필드 목록에서 필수 필드로 이동, 기본 이미지 경로 계약(클라이언트가 미설정 시 기본 경로 명시 전송) 서술 추가.
- 수정: "3분법(미전송=유지·경로=교체·빈 문자열=제거)" 문구·빈 문자열 예시를 "2분법(미전송=유지·경로=검증 후 교체), 빈 문자열=400 MEMBER-008" 로 교체.
