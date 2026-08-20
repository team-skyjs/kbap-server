# Contract: 주문 API (KB-337)

인증 JWT 필수(회원 전용)·`X-API-Version: 1.0`·봉투 `BaseResponse`. 오류: `ORDER-001`(400 — 주문 검증: 빈 항목·수량 0 등), `ORDER-002`(404 — 주문 없음/타인 주문), `ORDER-003`(409 — 이미 주문한 스캔).

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
- `items` 1~50개 필수. `menuName` 필수(≤100자), `quantity ≥ 1`, `price ≥ 0` 옵셔널, `foodId` **필수**(미등록 음식도 스캔 시점에 등록되므로 스캔 응답의 foodId 를 그대로 보낸다).
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
  "totalQuantity": 6, "totalPrice": 38500, "thumbnails": ["…"],
  "items": [ { "menuName": "순두부찌개", "quantity": 2, "price": 9000, "foodId": 7 },
             { "menuName": "반찬", "quantity": 2, "price": null, "foodId": 812 } ] }
```

- `totalPrice` = price 있는 항목의 price×quantity 합(전 항목 price null 이면 0). `totalQuantity`·`thumbnails`·`roadAddress`·`orderedAt` 은 리스트와 동일 의미.
- 본인 주문 아님·부재 → 404 `ORDER-002`(소유 노출 방지 — 동일 코드).

## 클라이언트 공유 요점

1. **좌표는 저장용으로만 보낸다** — 응답에 좌표는 절대 없다. 주소가 null 이면 위치 영역을 숨긴다(미동의·변환 실패 모두 같은 표현).
2. **가격 null = "included"류 표기** — 총가격 합산에서 빠진다. 환산(about $N)은 기존 환율 계약으로 클라이언트가 수행.
3. `thumbnails` 는 항상 URL 배열(대체 이미지 포함) — null 체크 불필요, 개수(1~4)는 항목 수를 따른다. 배치가 실사진을 채우면 다음 조회부터 자동 반영.
4. **주문하기 버튼은 스캔당 1회** — 409 `ORDER-003` 을 받으면 이미 주문된 스캔이니 버튼을 비활성 처리한다(더블탭은 서버가 원자적으로 막아준다).
5. 목업의 공유 카드·사진 교체·장소 편집은 이 계약에 없다(범위 밖).
