# Contract: 주문 API (KB-337)

인증 JWT 필수(회원 전용)·`X-API-Version: 1.0`·봉투 `BaseResponse`. 오류 코드:
- `COMMON-002`(400) — 요청 형식 검증 실패(빈 항목·수량 0/상한 초과·좌표 한쪽만·foodId 0 이하·항목 51개 등 Bean Validation)
- `ORDER-001`(400) — 존재하지 않는(또는 조회 불가) 음식을 주문한 경우
- `SCAN-001`(400) — 본인이 업로드하지 않은 imagePath
- `ORDER-002`(404) — 주문 없음/타인 주문(구분하지 않음)
- `ORDER-003`(409) — 이미 주문한 메뉴판
- `FOOD-002`(400) — 커서 형식 오류

## POST /api/orders — 주문 저장

```json
{
  "imagePath": "scan/42/menu.jpg",
  "items": [
    { "menuName": "순두부찌개", "quantity": 2, "price": 9000, "foodId": 7 },
    { "menuName": "수제비", "quantity": 1, "price": null, "foodId": 812 }
  ],
  "latitude": 37.5636, "longitude": 126.9834
}
```

- `imagePath` 필수 — 스캔 요청에 썼던 값 그대로. **스캔 1회당 주문 1회** — 같은 스캔으로 재주문은 409 `ORDER-003`.
- `items` 1~50개 필수. `menuName` 필수(≤100자), `1 ≤ quantity ≤ 999`, `0 ≤ price ≤ 10,000,000` 옵셔널, `foodId` **필수**(미등록 음식도 스캔 시점에 등록되므로 스캔 응답의 foodId 를 그대로 보낸다).
- `latitude`/`longitude` 옵셔널 — **둘 다 보내거나 둘 다 생략**. 서버가 도로명 주소로 변환해 함께 저장(실패 시 로그만, 주문 성공).
- 응답: `{ "orderId": 123 }`.
- 타인이 업로드한 이미지 경로면 400 `SCAN-001`(기존 스캔 이미지 검증 코드 재사용).

## GET /api/orders?cursor=&size= — 리스트 (최신순)

```json
{ "totalCount": 14, "items": [
    { "orderId": 123, "orderedAt": 1765700640000, "roadAddress": "서울 중구 소공로 51", 
      "totalQuantity": 6, "thumbnails": ["https://…/a.webp", "https://…/food_not_found.png"] }
  ], "hasNext": true, "nextCursor": "123" }
```

- `size` 기본 10·최대 30(초과 값은 30으로 자름). `cursor` = 직전 응답 `nextCursor` 그대로(불투명 문자열 — 형식이 잘못되면 400 `FOOD-002`(공용 커서 오류 코드)).
- `totalCount` = 회원 총 주문 수. `orderedAt` = 밀리초 epoch. `roadAddress` null 가능.
- `thumbnails` 최대 4 — 앞 4개 항목 순서대로. 음식 이미지가 없으면 **기본 대체 이미지 URL**(`images/webp/default_miss_food/food_not_found.png`)로 채워진다.

## GET /api/orders/{orderId} — 상세

```json
{ "orderId": 123, "orderedAt": 1765700640000, "roadAddress": "서울 중구 소공로 51",
  "totalQuantity": 4, "totalPrice": 18000,
  "items": [ { "menuName": "순두부찌개", "quantity": 2, "price": 9000, "foodId": 7,
               "imageRef": "https://cdn.example.com/images/webp/sundubu.webp" },
             { "menuName": "반찬", "quantity": 2, "price": null, "foodId": 812,
               "imageRef": "https://cdn.example.com/images/webp/default_miss_food/food_not_found.png" } ] }
```

- `totalPrice` = price 있는 항목의 price×quantity 합(전 항목 price null 이면 0).
- **상세에는 `thumbnails` 가 없다** — 음식 사진은 항목마다 `items[].imageRef` 로 내려간다(사진 없으면 기본 대체 이미지). 리스트의 `thumbnails` 는 카드용으로 유지.
- 본인 주문 아님·부재 → 404 `ORDER-002`(소유 노출 방지 — 동일 코드).

## 클라이언트 공유 요점

1. **좌표는 저장용으로만 보낸다** — 응답에 좌표는 절대 없다. 주소가 null 이면 위치 영역을 숨긴다(미동의·변환 실패 모두 같은 표현).
2. **가격 null = "included"류 표기** — 총가격 합산에서 빠진다. 환산(about $N)은 기존 환율 계약으로 클라이언트가 수행.
3. `thumbnails` 는 항상 URL 배열(대체 이미지 포함) — null 체크 불필요, 개수(1~4)는 항목 수를 따른다. 배치가 실사진을 채우면 다음 조회부터 자동 반영.
4. 상세의 음식 사진은 `items[].imageRef` — 리스트의 `thumbnails` 와 역할이 다르다(카드 요약 vs 항목별).
5. **주문하기 버튼은 스캔당 1회** — 409 `ORDER-003` 을 받으면 이미 주문된 스캔이니 버튼을 비활성 처리한다(더블탭은 서버가 원자적으로 막아준다).
6. 목업의 공유 카드·사진 교체·장소 편집은 이 계약에 없다(범위 밖).
