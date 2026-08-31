# Research: 주문 내역·주문 음식 이력 저장 (KB-337)

## R1. 역지오코딩 — Google Geocoding API, seam 은 `common.port.place`

- **Decision**: seam `common.port.place.ReverseGeocoder` — `fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String?`. **실패(오류·타임아웃·결과 없음·관할 밖)는 전부 warn 로그 + null** — KB-349 환율 포트와 같은 "폴백을 계약이 소유" 패턴이라 호출자(주문 저장)는 try/catch 없이 null 주소로 진행한다.
- 구현 `api.infra.place.GoogleGeocoder` — `GET https://maps.googleapis.com/maps/api/geocode/json?latlng={lat},{lng}&language=ko&key=...` (클래식 Geocoding API — Places (New) 의 `places.googleapis.com` 과 **호스트가 다르다**). 응답 `results[0].formatted_address` 를 채택하고 `status != OK` 는 null. RestClient + 매퍼 직접 소유(Boot 4 이중 클래스패스 함정), connect 1s / read 2s(사용자 요청 경로).
- 키는 **`kbap.google.places-api-key` 재사용**(같은 GCP 키). **운영 후속 필수**: 콘솔에서 ① Geocoding API 사용 설정 ② 키 API 제한 목록에 Geocoding API 추가 — 누락 시 `REQUEST_DENIED` 로 주소가 조용히 계속 null(주문은 성공하므로 로그로만 발견됨).
- **Alternatives**: 국내 도로명주소 API — 표준 포맷은 더 정확하나 키 발급·승인 절차가 별도이고 사용자 확정이 Google. 기각. Places (New) 의 reverseGeocode — 아직 Geocoding 전용 클래식이 표준 경로. 기각.

## R2. 저장 흐름 — 역지오코딩은 트랜잭션 밖 선행

- **Decision**: `OrderService.createOrder()` 구조 — ① (좌표 있으면) `reverseGeocoder.getRoadAddressOrNull()` 호출(트랜잭션 밖) → ② `@Transactional` 로 Order + OrderItem N 건 저장. 헌법 추가 제약("외부 호출을 트랜잭션 안에서 잡지 않는다") 준수.
- 응답은 저장된 주문 id 정도의 최소 계약(스캔→주문 화면은 저장 성공만 알면 됨).

## R3. 엔티티·스키마

- **Decision**: `common.domain.order` 신규 컨텍스트 — `Order`(memberId, latitude/longitude `decimal(10,7)` null, roadAddress `varchar(200)` null) + `OrderItem`(orderId, foodId **not null** — 미등록 음식도 스캔 시 food 생성, menuName `varchar(100)`, quantity int, price int null). 둘 다 `BaseEntity` 상속(주문일 = `createdAt` 재사용 — 별도 컬럼 없음), JPA 연관 없이 `Long` id 참조(헌법 IV). 좌표 정밀도·주소 길이는 `ReviewPlace` 와 동일 값.
- Flyway 2 테이블 + 인덱스 `idx_orders_recent(member_id, created_at)`·`idx_order_item_order(order_id)`. 테이블명 `orders`(MySQL 예약어 `order` 회피)·`order_item`.
- `ModuleBoundaryTest` 허용 맵에 `"order" to emptySet()` 추가 — OrderItem 의 foodId 는 Long 값이라 도메인 의존이 없다. 썸네일 join(foodId → Food.imageRef)은 api 계층(`OrderService`)이 `FoodJpaRepository.findAllById` 로 수행.

## R4. 조회 계약 — 리뷰 목록 관례(불투명 커서) 축소 적용

- **Decision**: `GET /api/orders` — 최신순 단일 정렬이라 커서는 **id 숫자 문자열**(리뷰 LATEST 커서와 동일 규칙, `CursorParser` 재사용). 응답 `{ totalCount, items[], hasNext, nextCursor }` — `totalCount`(총 주문 수)는 Jira DoD 명시라 리뷰 페이지 계약에 추가. size 기본 10·최대 30.
- 리스트 항목: `orderId`·`orderedAt`(밀리초 long — `createdAt` epoch 변환)·`roadAddress?`·`totalQuantity`(항목 수량 합)·`thumbnails[]`(최대 4).
- `GET /api/orders/{orderId}` — 리스트 항목 정보 + `items[]`(menuName·quantity·price?·foodId?) + `totalPrice`(price 있는 항목의 price×quantity 합). 본인 아니면 `ORDER-002`(404 성격) — id 존재 노출을 피해 not-found 와 동일 코드로 통일할지 여부는 기존 관례(리뷰 소유 검증) 따름 — 신규 `ErrorCode` 3개(`ORDER-001` 주문 검증, `ORDER-002` 주문 없음, `ORDER-003` 이미 주문한 스캔 — 409).
- 썸네일 규칙: 주문 항목 저장 순 앞 4개의 `resolveImageUrlOrDefault(food)` — **음식 이미지가 null 이어도 기본 대체 URL 로 채워진다**(FR-006). foodId 는 NOT NULL 이라 제외 분기가 없다. N+1 방지: 주문 페이지의 전 foodId 를 모아 `findAllById` 1회.

## R5. 검증·API 계약 소품

- **스캔 1회당 주문 1회**(사용자 확정): `orders.image_path varchar(512) NOT NULL` + `UNIQUE uq_orders_image_path`. 요청에 `imagePath` 필수 — 스캔 요청과 같은 값. 위반은 `DataIntegrityViolationException` 을 잡아 `ORDER-003`(409) — 선조회 없이 DB 유일성으로 더블탭 경합까지 원자 방어(동시성 방어 수위 규약의 "최소 수단 = unique 제약"). imagePath 소유 검증은 `ImageUploadService.verifyImageAccess` 재사용 — 실패 시 기존 `SCAN_IMAGE_NOT_VERIFIED`(SCAN-001) 재사용(같은 의미의 새 코드를 만들지 않는다).
- 요청 DTO: `imagePath @NotBlank @Size(max=512)`, `items` `@NotEmpty`·`@Size(max=50)`, 항목 `foodId @NotNull`, `quantity @Min(1)`, `menuName @NotBlank @Size(max=100)`, `price @Min(0)?`, 좌표는 위도 -90~90·경도 -180~180(둘 다 오거나 둘 다 없음 — `ReviewPlaceRequest.coordinatesComplete` 패턴).
- 신규 경로 `/api/orders` 는 `WebConfig` JWT 보호 목록 등록 확인(두 번 밟은 함정 — CLAUDE.md). `X-API-Version: 1.0` 기본 매핑(버전 조건 없음).
- swagger: `OrderApi` 인터페이스(문서 애너테이션)와 컨트롤러(스프링 애너테이션) 분리 — 파라미터 애너테이션 위치 규약.

## R6. 테스트 전략

- `GoogleGeocoderTest`(MockRestServiceServer): 요청 latlng·language=ko, OK → formatted_address, ZERO_RESULTS → null, REQUEST_DENIED → null, HTTP 오류·파싱 실패 → null.
- `OrderControllerTest`(MockMvc + Testcontainers, fake `ReverseGeocoder` @Primary — `FakeExchangeRateClient` 패턴): 저장(좌표 유/무·역지오코딩 실패·빈 항목 400·수량 0 400·**같은 imagePath 재주문 409**·타인 imagePath 거절), 리스트(총 개수·최신순·커서·썸네일 4 제한·기본 대체 이미지·빈 목록), 상세(총가격·가격 null 합산 제외·타인 404·부재 404).
- `OrderTest`·`OrderItemTest`(순수 단위): 검증 규칙. Flyway 스키마는 통합 테스트의 `ddl-auto=validate` 가 검증.
