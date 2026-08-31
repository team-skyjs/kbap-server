# Implementation Plan: 주문 내역·주문 음식 이력 저장

**Branch**: `kb-337-order-history` | **Date**: 2026-08-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-337-order-history/spec.md`

## Summary

스캔 결과에서 고른 메뉴(수량·가격 스냅샷)를 주문 1건 + 항목 N건으로 저장하고, 최신순 커서 리스트·상세 조회를 제공한다. 좌표가 오면 **Google Geocoding 으로 도로명 주소를 변환해 좌표·주소를 저장**(실패 시 warn 로그 + 주소 null, 주문은 성공)하며 응답엔 주소만 노출한다. 썸네일은 음식 마스터 이미지를 표시 시점에 읽고 없으면 기본 대체 URL(`resolveImageUrlOrDefault` 재사용) — DB 에 대체 이미지를 저장하지 않는다. Refs KB-337 (KB-351 흡수).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1
**Primary Dependencies**: RestClient(Geocoding 어댑터 — Google Places 어댑터 골격 재사용), Flyway, springdoc
**Storage**: MySQL — 신규 `orders`·`order_item` 테이블(Flyway). 좌표 `decimal(10,7)`·주소 `varchar(200)`(ReviewPlace 와 동일 스펙)
**Testing**: Kotest BehaviorSpec — MockRestServiceServer(Geocoder 어댑터)·순수 단위(엔티티 검증)·MockMvc+Testcontainers(주문 API, fake `ReverseGeocoder` @Primary)
**Target Platform**: `:api` 만. batch 무관
**Project Type**: web-service (모듈러 모놀리스 `:common`·`:api`)
**Performance Goals**: 주문 저장 = 역지오코딩 1회(좌표 있을 때만, ≤3s 한도) + INSERT. 리스트 조회 = 주문 페이지 1쿼리 + 항목 집계 + food 벌크 1쿼리(N+1 금지)
**Constraints**: 역지오코딩은 트랜잭션 밖·실패가 저장을 막지 않음, 좌표 응답 미노출, 이름·가격 스냅샷 불변, 커서 페이징(리뷰 관례), 회원 전용(JWT)
**Scale/Scope**: 신규 도메인 컨텍스트 1(`order` — 엔티티 2·리포지토리 2), seam 1 + 어댑터 1, API 3개(저장·리스트·상세), Flyway 1, ErrorCode 2

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 어댑터·엔티티·컨트롤러 테스트를 Red 먼저(research R6). |
| II. Bounded Contexts | PASS | 신규 `common.domain.order` 는 타 도메인 무의존(`"order" to emptySet()` 을 `ModuleBoundaryTest` 맵에 추가). foodId 는 Long 값 참조. 썸네일 join 은 `api.order` 조합 계층. |
| III. Layered Dependency | PASS | seam `common.port.place.ReverseGeocoder` → 어댑터 `api.infra.place.GoogleGeocoder` → 조립 `PlaceConfig`(기존 config 에 빈 추가). 어댑터 직접 참조는 config 뿐. |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델, `BaseEntity` 상속, JPA 연관 없음(Long id), public 리포지토리, Flyway(owner=api) 스키마. 트랜잭션은 `OrderService` public 메서드가 명시 선언. |
| V. Language Policy | N/A | 메뉴명은 사용자가 보낸 스냅샷 문자열 — 번역 파이프라인 대상 아님. |
| 추가 제약 — 외부 호출 트랜잭션 밖 | PASS | 역지오코딩을 저장 트랜잭션 앞에서 수행(research R2). |

**Post-Phase-1 재검토**: data-model·contracts 작성 후 판정 불변 — 도메인 새 방향 없음, 도메인/영속 모델 직접 노출 없음(응답 DTO 분리).

## Project Structure

### Documentation (this feature)

```text
specs/kb-337-order-history/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── order-api.md
└── tasks.md            # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── domain/order/model/Order.kt              # 신규: memberId·좌표(null)·roadAddress(null), BaseEntity
├── domain/order/model/OrderItem.kt          # 신규: orderId·foodId(null)·menuName·quantity·price(null)
├── domain/order/OrderJpaRepository.kt       # 신규: 커서 페이지·count
├── domain/order/OrderItemJpaRepository.kt   # 신규: findByOrderIdIn(집계용)
├── core/error/ErrorCode.kt                  # 수정: ORDER-001(주문 검증)·ORDER-002(주문 없음)
└── port/place/ReverseGeocoder.kt            # 신규 seam: getRoadAddressOrNull(lat,lng) — 실패 null

api/src/main/kotlin/com/kbap/api/
├── order/OrderController.kt                 # 신규: POST /api/orders·GET /api/orders·GET /api/orders/{id}
├── order/OrderApi.kt                        # 신규: swagger 인터페이스
├── order/OrderCreateRequest.kt              # 신규: items·좌표(둘 다/둘 다 아님)
├── order/OrderResponses.kt                  # 신규: 리스트 페이지(totalCount·items·hasNext·nextCursor)·상세
├── order/OrderService.kt                    # 신규: createOrder(지오코딩 선행)·getOrderPage·getOrderDetail
├── infra/place/GoogleGeocoder.kt            # 신규 어댑터 (+ HTTP interface 동일 파일)
└── core/config/PlaceConfig.kt               # 수정: ReverseGeocoder 빈 추가(같은 키)

api/src/main/resources/db/migration/
└── V<생성시각>__order_tables.sql            # 신규: orders·order_item + 인덱스

api/src/test/kotlin/com/kbap/api/
├── order/OrderControllerTest.kt             # 신규 (fake ReverseGeocoder 동봉)
├── order/FakeReverseGeocoder.kt             # 신규 (@Primary test config)
├── infra/place/GoogleGeocoderTest.kt        # 신규 (MockRestServiceServer)
└── architecture/ModuleBoundaryTest.kt       # 수정: "order" to emptySet()

common/src/test/kotlin/com/kbap/common/domain/order/
└── model/OrderTest.kt                       # 신규: 엔티티 검증 규칙
```

**Structure Decision**: seam 은 기존 `common.port.place` 에 합류(장소 관심사), 어댑터는 기존 `api.infra.place` 에 합류, 빈 조립은 기존 `PlaceConfig` 확장 — 새 패키지·새 config 를 만들지 않는다. API 는 `com.kbap.api.order` 기능 패키지 하나. 도메인 서비스는 두지 않는다 — 검증은 엔티티(생성 팩토리), 조합·트랜잭션은 `OrderService`(api 전용 서비스).

## Complexity Tracking

위반 없음 — 표 생략.
