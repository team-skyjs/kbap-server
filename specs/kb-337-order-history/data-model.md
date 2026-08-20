# Data Model: KB-337 주문 내역

## `orders` (신규 — `Order`, `common.domain.order.model`)

| 컬럼 | 타입 | null | 의미 |
|---|---|---|---|
| id·status·created_at·updated_at | BaseEntity 공통 | — | **주문일 = created_at**(별도 컬럼 없음). 소프트삭제 상속(현재 삭제 경로 없음) |
| member_id | bigint | NOT NULL | 주문자. FK 제약은 Flyway(ON DELETE 없음) |
| image_path | varchar(512) | NOT NULL | 스캔 식별자. **UNIQUE — 스캔 1회당 주문 1회 강제**(더블탭 원자 방어) |
| latitude | decimal(10,7) | NULL | '주문하기' 순간 좌표. **응답 미노출** |
| longitude | decimal(10,7) | NULL | 〃 (latitude 와 함께만 존재) |
| road_address | varchar(200) | NULL | 역지오코딩 결과. 실패·좌표 없음이면 NULL |

- 인덱스: `idx_orders_recent (member_id, created_at)` — 리스트 최신순. 유일 제약: `uq_orders_image_path (image_path)`.
- 검증(엔티티): 좌표는 둘 다 있거나 둘 다 없음, 위도 -90~90·경도 -180~180(ReviewPlace 와 동일 범위).

## `order_item` (신규 — `OrderItem`)

| 컬럼 | 타입 | null | 의미 |
|---|---|---|---|
| order_id | bigint | NOT NULL | 소속 주문(Long 참조, JPA 연관 없음) |
| food_id | bigint | NOT NULL | 음식 참조 — 미등록 메뉴도 스캔 시점에 food 로 저장되므로 항상 존재(사용자 확정 2026-08-20) |
| menu_name | varchar(100) | NOT NULL | **저장 시점 스냅샷** — 이후 음식 마스터 변경과 무관 |
| quantity | int | NOT NULL | ≥ 1 |
| price | int | NULL | 단가 스냅샷(원화 정수). 스캔 미인식이면 NULL — 총가격 합산에서 제외 |

- 인덱스: `idx_order_item_order (order_id)`.
- 중복 메뉴 병합 안 함 — 요청 순서대로 저장.

## seam `ReverseGeocoder` (`common.port.place`)

```
fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String?
```
- null: 제공처 오류·타임아웃·파싱 실패·결과 없음(해외 좌표 등). 예외를 던지지 않는다(실패 사유는 어댑터가 warn 로그).
- 구현 `api.infra.place.GoogleGeocoder` — Google Geocoding API(`maps.googleapis.com`), `language=ko`, `results[0].formatted_address`.

## 파생 값 (저장하지 않음 — 조회 시 계산)

- `totalQuantity` = Σ item.quantity (주문별)
- `totalPrice` = Σ (item.price × item.quantity), price NULL 항목 제외
- `thumbnails` = 항목 저장 순서대로 앞 4개 항목의 `resolveImageUrlOrDefault(food)` — food 는 페이지 전체 foodId 벌크 조회(N+1 금지). **음식 imageRef 가 NULL 이어도 기본 대체 URL 로 채워진다. DB 에 대체 이미지를 저장하지 않는다(FR-006 — 이미지 생성 배치 대상 선정 보호).**

## 도메인 방향

`order` 컨텍스트는 타 도메인 무의존 — `ModuleBoundaryTest` 허용 맵에 `"order" to emptySet()` 추가. food 참조는 Long id·api 계층 join.
