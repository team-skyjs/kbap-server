# API Contract: 장소(식당) 검색 — 신규 엔드포인트 (kb-274)

리뷰 API 와 분리된 **별도 컨트롤러**(`com.kbap.api.place.PlaceController`). 서버가 카카오 로컬 키워드 검색을 대신 호출한다 — 검색 키워드는 서버가 `음식점` 으로 고정하고, 클라이언트는 사용자 위치(위도·경도)만 보낸다. 응답은 `BaseResponse<T>` 봉투, 경로는 신규 리소스 규약(KB-321)에 따라 `ApiPaths.API` 기준.

## GET /api/places

**인증**: 필수 — `JwtAuthenticationFilter` 보호 경로(`/api/places`, `/api/places/*`) + `@AuthMemberId`.

**Query Parameters**

| 파라미터 | 타입 | 필수 | 제약 |
|----------|------|------|------|
| latitude | decimal | ✅ | -90~90. 누락·범위 밖 → HTTP 400(기존 validation 공통 처리) |
| longitude | decimal | ✅ | -180~180. 누락·범위 밖 → HTTP 400 |

**성공 응답** (`BaseResponse.ok`)

```json
{
  "success": true,
  "payload": {
    "items": [
      {
        "name": "한밥집 강남점",
        "address": "서울 강남구 테헤란로 123",
        "kakaoPlaceId": "27290047",
        "latitude": 37.4979502,
        "longitude": 127.0276368
      }
    ]
  }
}
```

- `items` — 가까운 순 최대 5건(페이징 없음). 결과 없음은 빈 배열(오류 아님).
- 항목 스키마는 리뷰 저장 `place` 요청과 동일 형태 — 클라이언트는 선택한 item 을 그대로 리뷰 작성 `place` 로 전달한다.
- 카카오 응답의 항목 결측(예: 도로명주소 없음)은 해당 필드 `null` 로 반환.

**실패 응답**

| 상황 | HTTP | code |
|------|------|------|
| latitude·longitude 누락·범위 밖 | 400 | 기존 validation 공통 코드 |
| 미인증 | 401 | 기존 인증 공통 코드 |
| 카카오 호출 실패(장애·타임아웃·키 미설정) | 502 | `PLACE-001` (신규 채번 — 외부 장소 검색 실패) |

- `PLACE-001` 은 검색 전용 — 리뷰 작성·조회·수정은 이 코드와 무관하며 카카오 장애의 영향을 받지 않는다(FR-003).

## 내부 계약 (참고 — seam)

- `common.port.place.PlaceSearchClient.search(query: String, longitude: BigDecimal, latitude: BigDecimal): List<FoundPlace>` — Spring-free 계약. `FoundPlace(name·address·kakaoPlaceId·latitude·longitude — 전 항목 nullable, name 은 카카오가 항상 주므로 non-null)`.
- 구현 `:infra:place` `KakaoPlaceSearchClient` — `GET https://dapi.kakao.com/v2/local/search/keyword.json?query=&x=&y=&sort=distance&size=5` (x=경도, y=위도 — 가까운 순 상위 5건), 헤더 `Authorization: KakaoAK <kbap.kakao.rest-api-key>`. 실패·키 미설정 → `BusinessException(PLACE-001)`.
