# API Contract: 장소(식당) 검색 — 신규 엔드포인트 (kb-274)

리뷰 API 와 분리된 **별도 컨트롤러**(`com.kbap.api.place.PlaceController`). 서버가 카카오 로컬 키워드 검색을 대신 호출한다. 용도가 달라 **두 엔드포인트로 분리**한다 — 주변 탑10 은 파라미터가 좌표뿐이라 추후 캐싱 후보(격자 반올림 키), 키워드 검색은 페이징 계약. 응답은 `BaseResponse<T>` 봉투, 경로는 신규 리소스 규약(KB-321)에 따라 `ApiPaths.API` 기준.

**인증**: 둘 다 필수 — `JwtAuthenticationFilter` 보호 경로(`/api/places/*`) + `@AuthMemberId`.

## GET /api/places/nearby — 주변 식당 탑10

화면 진입 시 호출. 검색 키워드는 서버가 `음식점` 으로 고정한다.

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
        "latitude": 37.4979502,
        "longitude": 127.0276368
      }
    ]
  }
}
```

- `items` — 가까운 순 최대 10건(페이징 없음). 결과 없음은 빈 배열(오류 아님).

## GET /api/places/search — 식당 키워드 검색

사용자가 원하는 식당을 직접 검색할 때 호출. 결과는 사용자 위치 기준 가까운 순.

| 파라미터 | 타입 | 필수 | 제약 |
|----------|------|------|------|
| query | string | ✅ | 빈/공백 불가, 최대 100자 → HTTP 400 |
| latitude | decimal | ✅ | -90~90 |
| longitude | decimal | ✅ | -180~180 |
| page | int | — | 기본 1, 1~45(카카오 허용 범위) |

**성공 응답** (`BaseResponse.ok`)

```json
{
  "success": true,
  "payload": {
    "items": [ { "name": "...", "address": "...", "latitude": 0, "longitude": 0 } ],
    "hasNext": true
  }
}
```

- `items` — 페이지당 최대 15건. `hasNext` 로 다음 페이지 유무 판단.

## 공통

- 항목 스키마는 리뷰 저장 `place` 요청과 동일 형태 — 클라이언트는 선택한 item 을 그대로 리뷰 작성 `place` 로 전달한다(→ `place_source=KAKAO_PLACE`).
- 카카오 응답의 항목 결측(예: 도로명주소 없음)은 해당 필드 `null` 로 반환.

**실패 응답**

| 상황 | HTTP | code |
|------|------|------|
| 파라미터 누락·범위 밖 | 400 | 기존 validation 공통 코드 |
| 미인증 | 401 | 기존 인증 공통 코드 |
| 카카오 호출 실패(장애·타임아웃·키 미설정·파싱 실패) | 502 | `PLACE-001` (외부 장소 검색 실패) |

- `PLACE-001` 은 검색 전용 — 리뷰 작성·조회·수정은 이 코드와 무관하며 카카오 장애의 영향을 받지 않는다(FR-003).

## 내부 계약 (참고 — seam)

- `common.port.place.PlaceSearchClient` — Spring-free 계약: `searchNearby(query, longitude, latitude): List<FoundPlace>` / `searchPage(query, longitude, latitude, page): PlaceSearchPage(items, hasNext)`. `FoundPlace(name·address·latitude·longitude — name 외 전 항목 nullable)`. 외부 지도 제공자 식별자(kakao place id)는 받지도 저장하지도 않는다.
- 구현 `:infra:place` — HTTP 인터페이스 선언형(`KakaoLocalApi` `@GetExchange` + `HttpServiceProxyFactory`). `GET https://dapi.kakao.com/v2/local/search/keyword.json?query=&x=&y=&sort=distance&page=&size=` (x=경도, y=위도 / nearby size=10·page=1, search size=15). 헤더 `Authorization: KakaoAK <kbap.kakao.rest-api-key>`. 메시지 컨버터는 클라이언트 소유 Jackson 3(tools.jackson + Kotlin 모듈)로 단일 고정 — ambient 컨버터 의존 금지(Boot 4 Jackson 이중 클래스패스 함정). 실패·키 미설정·파싱 실패 → `BusinessException(PLACE-001)`.
