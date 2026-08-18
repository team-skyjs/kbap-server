# Contract: GET /api/reviews 접근 범위 (KB-348 이후)

응답 계약(항목 형태·페이징 봉투)은 **불변** — 바뀌는 것은 인증 요구뿐이다. `X-API-Version` 헤더는 기존 규약대로 필수.

## 엔드포인트별 인증 요구

| 엔드포인트 | 변경 전 | 변경 후 |
|---|---|---|
| `GET /api/reviews` (전체·foodId·countryCode·cursor) | 401 (회원 전용) | **비회원 허용** |
| `POST /api/reviews` (작성) | 401 | 401 (불변) |
| `PATCH /api/reviews/{id}` (수정) | 401 | 401 (불변) |
| `DELETE /api/reviews/{id}` (삭제) | 401 | 401 (불변) |
| `POST /api/reviews/{id}/like` (좋아요) | 401 | 401 (불변) |
| `GET /api/reviews/me` (내 리뷰) | 401 | 401 (불변) |

## 비회원 조회 규칙

- 항목 형태는 회원과 동일(`ReviewResponse` — createdAt epoch millis·author.profileImageUrl 포함, KB-334 계약).
- `likedByMe` 는 전 항목 false.
- 차단·신고에 따른 조회자별 제외는 **미적용**(개념 미성립) — 소프트 삭제 음식 리뷰 제외(전원 규칙)는 동일 적용.
- `countryCode`·`cursor`·`lang` 파라미터 의미 불변 — `lang` 누락·빈 값은 비회원도 400.
- 탈퇴 회원 토큰(유효하지만 비활성)은 토큰의 id 기준으로 동작 — 실질적으로 차단 목록이 비어 비회원과 동일한 결과.
