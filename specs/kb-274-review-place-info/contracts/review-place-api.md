# API Contract: 리뷰 식당(장소) 정보 — 기존 리뷰 API 델타 (kb-274)

리뷰 API 에는 신규 엔드포인트 없음 — 요청/응답 스키마 확장만(장소 **검색**은 별도 컨트롤러: [place-search-api.md](place-search-api.md), 신규 ErrorCode `PLACE-001` 도 검색 전용). 모든 응답은 `BaseResponse<T>` 봉투, 경로는 `ApiPaths.V1` 기준(기존 그대로).

## 요청 델타 — POST /api/v1/reviews · PUT /api/v1/reviews/{reviewId}

`ReviewCreateRequest`·`ReviewUpdateRequest` 에 선택 필드 추가:

```json
{
  "foodId": 1,
  "rating": 4,
  "content": "정말 맛있어요",
  "imagePaths": ["images/review/..."],
  "place": {
    "name": "한밥집 강남점",
    "address": "서울 강남구 테헤란로 123",
    "latitude": 37.4979502,
    "longitude": 127.0276368
  }
}
```

- `place` — **선택**(생략/null 허용). 세 형태가 유효하며 출처는 서버가 유도한다:
  - **식당 선택**(`name`+좌표 양쪽): 검색 결과 항목 그대로 → `place_source=KAKAO_PLACE`
  - **사용자 입력 텍스트**(GPS 미동의): `name` 만 → `place_source=MANUAL`
  - **작성자 좌표**(식당 미선택 + GPS 동의): `latitude`·`longitude` 둘 다 필수, 식당 항목 없음 → `place_source=AUTHOR_LOCATION`. 좌표는 반올림 없이 원값 저장.
  - 좌표가 한쪽만 오면 400. 빈 객체 `{}` 는 위치 없음으로 정규화. 외부 지도 제공자 식별자(kakao place id)는 받지도 저장하지도 않는다 — 딥링크·클러스터링은 명·주소·좌표로 충분.
- 수정(PUT)은 전량 교체: `place` 생략 시 기존 장소 정보 **제거**(content·imagePaths 와 동일 규칙).
- 검증 실패(길이 초과·좌표 범위 밖) → HTTP 400 + `BaseResponse.fail`(기존 validation 공통 처리, `COMMON` 에러 코드) — 새 `ErrorCode` 채번 없음.

| 항목 | 타입 | 제약 |
|------|------|------|
| place.name | string? | 최대 100자 |
| place.address | string? | 최대 200자 |
| place.latitude | number? | -90 ~ 90 (name 없으면 longitude 와 함께 필수) |
| place.longitude | number? | -180 ~ 180 (name 없으면 latitude 와 함께 필수) |

## 응답 델타 — 리뷰가 노출되는 모든 조회

`ReviewResponse` 에 `place` 추가(작성·수정 응답, GET /api/v1/foods/{foodId}/reviews 목록, 내 리뷰 목록, 음식 상세 리뷰 섹션 — 전부 `ReviewResponse.from` 경유라 동일 반영):

```json
{
  "reviewId": 42,
  "foodId": 1,
  "memberId": 7,
  "rating": 4,
  "content": "정말 맛있어요",
  "imageUrls": [],
  "createdAt": "2026-08-01T12:00:00",
  "author": { "...": "기존 그대로" },
  "place": {
    "name": "한밥집 강남점",
    "address": "서울 강남구 테헤란로 123",
    "latitude": 37.4979502,
    "longitude": 127.0276368
  }
}
```

- 장소 정보가 없는 리뷰(기존 리뷰 포함)는 `place: null`.
- 저장 당시 결측이던 항목은 항목 단위 `null` 로 그대로 반환.

## 무변경 확인 목록

- 경로·메서드·인증(`@AuthMemberId`)·에러 코드 체계 — 변경 없음.
- `place` 없이 보내는 기존 클라이언트 요청 — 동작 불변(하위 호환).
- 리뷰 삭제·신고·차단 필터·평점 집계(`RatingSummary`) — place 와 무관, 변경 없음.
