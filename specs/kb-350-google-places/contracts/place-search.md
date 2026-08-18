# Contract: 장소 API (KB-350 이후)

인증(JWT 필수)·경로·오류 코드(PLACE-001 = 외부 검색 실패 502)는 불변. 데이터 제공자만 카카오 → 구글로 바뀐다.

## GET /api/places/nearby — 화면 진입 시 주변 식당

- 요청: `latitude`·`longitude` (불변)
- 응답: 근처 식당 **최대 20건**, 거리순 (기존 형태 그대로)

```json
{ "success": true, "payload": { "items": [
  { "name": "한밥집 강남점", "address": "서울 강남구 테헤란로 123", "latitude": 37.4979502, "longitude": 127.0276368 }
] } }
```

## GET /api/places/search — 키워드 검색

- 요청: `query`·`latitude`·`longitude` — **`page` 파라미터 삭제** (보내도 400 없이 무시 — 전환기 호환)
- 응답: 관련도순 **최대 20건 단일 응답** — **`hasNext` 필드 삭제** (페이징 없음)

```json
{ "success": true, "payload": { "items": [ { "name": "...", "address": "...", "latitude": ..., "longitude": ... } ] } }
```

## 리뷰 장소 출처 (place.source)

| 값 | 의미 |
|---|---|
| `GOOGLE_PLACE` | **신설** — 전환 후 검색 결과로 선택한 식당 (신규 작성 기본) |
| `KAKAO_PLACE` | 기존 리뷰 보존용 — 신규 저장 없음(값 자체는 계속 유효) |
| `MANUAL` / `AUTHOR_LOCATION` | 불변 |

- 리뷰 작성/수정 요청의 `place.source` 에 `GOOGLE_PLACE` 허용, 조회 응답에도 노출.

## 클라이언트 공유 요점

1. 검색 응답에서 `hasNext` 가 사라지고 `page` 요청 파라미터가 무의미해진다 — 더보기 UI 제거.
2. 리뷰 작성 시 검색 결과 선택이면 `source: "GOOGLE_PLACE"` 로 전송.
3. 과거 리뷰 조회에는 `KAKAO_PLACE` 가 계속 내려온다 — 분기 유지 필요.
