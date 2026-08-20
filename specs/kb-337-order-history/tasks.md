# Tasks: 주문 내역·주문 음식 이력 저장 (KB-337)

**Input**: `specs/kb-337-order-history/` — spec.md·plan.md·research.md·data-model.md·contracts/order-api.md·quickstart.md

**Tests**: 헌법 원칙 I(Test-First) — 각 단계에서 테스트를 먼저 Red 로 확인 후 구현한다.

**Organization**: Foundational(도메인·스키마·seam·어댑터) → US1(주문 저장) → US2(리스트) → US3(상세) → Polish. US2·US3 은 US1 의 저장 데이터를 전제하므로 순차.

## Phase 1: Setup

- [x] T001 `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `ORDER_INVALID`(ORDER-001, 400)·`ORDER_NOT_FOUND`(ORDER-002, 404)·`ORDER_ALREADY_PLACED`(ORDER-003, 409) 추가 — `ErrorCodeStatusTest` 형식 검증 통과 확인

## Phase 2: Foundational — 도메인·스키마·역지오코딩 seam

- [x] T002 [P] [Red] `common/src/test/kotlin/com/kbap/common/domain/order/model/OrderTest.kt` 신설 — 좌표 둘 다/둘 다 아님 검증, 위도 -90~90·경도 -180~180 범위 위반 예외, imagePath blank 예외 (BehaviorSpec·한국어 설명)
- [x] T003 [P] [Red] `common/src/test/kotlin/com/kbap/common/domain/order/model/OrderItemTest.kt` 신설 — quantity 0 이하 예외, menuName blank·100자 초과 예외, price 음수 예외
- [x] T004 엔티티 신설 `common/src/main/kotlin/com/kbap/common/domain/order/model/Order.kt`(memberId·imagePath·latitude/longitude(BigDecimal?)·roadAddress?, BaseEntity 상속, `@Column` 길이 data-model.md 대로) + `OrderItem.kt`(orderId·foodId(필수)·menuName·quantity·price?, `place()` 팩토리). T002·T003 Green 확인
- [x] T005 [P] 리포지토리 신설 `common/src/main/kotlin/com/kbap/common/domain/order/OrderJpaRepository.kt` — `countByMemberId`, 커서 페이지(`memberId + (:cursor is null or id < :cursor) order by id desc`, Pageable limit) / `OrderItemJpaRepository.kt` — `findByOrderIdInOrderByIdAsc`, `findByOrderIdOrderByIdAsc`
- [x] T006 [P] Flyway 신설 `api/src/main/resources/db/migration/V<생성시각>__order_tables.sql` — `orders`(uq_orders_image_path·idx_orders_recent)·`order_item`(idx_order_item_order), FK 제약(member·orders, ON DELETE 없음), 헤더 주석에 KB-337 결정(스캔당 1회·좌표 미노출) 요약
- [x] T007 [P] seam 신설 `common/src/main/kotlin/com/kbap/common/port/place/ReverseGeocoder.kt` — `fun getRoadAddressOrNull(latitude: BigDecimal, longitude: BigDecimal): String?` (Spring-free)
- [x] T008 [Red] 어댑터 테스트 신설 `api/src/test/kotlin/com/kbap/api/infra/place/GoogleGeocoderTest.kt` (MockRestServiceServer, `FrankfurterExchangeRateClientTest` 골격) — 요청 URL `latlng={lat},{lng}`·`language=ko`·key 파라미터, `status=OK` → `results[0].formatted_address`, `ZERO_RESULTS` → null, `REQUEST_DENIED` → null(warn), HTTP 5xx → null, 파싱 불가 → null. Red 확인
- [x] T009 어댑터 구현 `api/src/main/kotlin/com/kbap/api/infra/place/GoogleGeocoder.kt` — RestClient(JDK HttpClient connect 1s/read 2s)·매퍼 직접 소유·`companion { create(apiKey); internal create(apiKey, builder) }`·base `https://maps.googleapis.com`. T008 Green 확인
- [x] T010 `api/src/main/kotlin/com/kbap/api/core/config/PlaceConfig.kt` 수정 — `@ConditionalOnMissingBean(ReverseGeocoder::class)` 빈 추가(기존 `kbap.google.places-api-key` 재사용)
- [x] T011 [P] `api/src/test/kotlin/com/kbap/api/architecture/ModuleBoundaryTest.kt` 허용 맵에 `"order" to emptySet()` 추가 — `./gradlew :api:test --tests "*ModuleBoundaryTest"` (arch 태그) 그린 확인
- [x] T012 [P] 테스트 fake 신설 `api/src/test/kotlin/com/kbap/api/order/FakeReverseGeocoder.kt` — 좌표→주소 가변 맵 + `failAll` 스위치, `@Configuration` 빈 등록 동봉(`FakeExchangeRateClient` 골격)

**Checkpoint**: `:common:test`(order 도메인)·`:api:test`(infra.place·architecture) 그린. 기존 기능 무변경.

## Phase 3: US1 — 스캔한 메뉴로 주문 저장 (P1) 🎯 MVP

**Goal**: `POST /api/orders` — 스냅샷 저장 + 역지오코딩(트랜잭션 밖) + 스캔당 1회 강제.

**Independent Test**: 메뉴 2종(수량 1·2) 저장 → 200 + orderId, DB 에 주문 1건·항목 2건. 같은 imagePath 재시도 → 409.

- [x] T013 [US1] [Red] `api/src/test/kotlin/com/kbap/api/order/OrderControllerTest.kt` 신설(MockMvc+Testcontainers, FakeReverseGeocoder 주입) — 저장 성공(항목 2건·DB 단정), 좌표 포함 시 좌표+주소 저장, 좌표 없이 저장(위치 null), fake 실패 시 좌표만 저장·주소 null·200, 빈 items 400 ORDER-001, quantity 0 400, 좌표 한쪽만 400, **같은 imagePath 재주문 409 ORDER-003**, 타인 imagePath 400(이미지 소유 검증), 미인증 401. Red 확인
- [x] T014 [US1] 요청 DTO 신설 `api/src/main/kotlin/com/kbap/api/order/OrderCreateRequest.kt` — imagePath·items(1~50)·좌표(coordinatesComplete 패턴), 항목 검증(R5), `toOrder(memberId, roadAddress)`/`toItems(orderId)` 변환
- [x] T015 [US1] 서비스 신설 `api/src/main/kotlin/com/kbap/api/order/OrderService.kt` — `createOrder`: 이미지 소유 검증(스캔의 `verifyImageAccess` 패턴 재사용) → 좌표 있으면 `reverseGeocoder.getRoadAddressOrNull()`(트랜잭션 밖) → `@Transactional` 저장, `DataIntegrityViolationException` → `ORDER_ALREADY_PLACED`
- [x] T016 [US1] 컨트롤러·swagger 신설 `api/src/main/kotlin/com/kbap/api/order/OrderController.kt`(`ApiPaths.API + "/orders"`, `@AuthMemberId`) + `OrderApi.kt`(문서 애너테이션만) + 응답 `OrderCreateResponse(orderId)`. `WebConfig` JWT 보호 경로에 `/api/orders` 커버 확인(패턴 미포함 시 추가). T013 Green 확인

**Checkpoint**: US1 단독 배포 가능 — 데이터 쌓이기 시작.

## Phase 4: US2 — 주문 리스트 조회 (P2)

**Goal**: `GET /api/orders` — totalCount·최신순 커서·썸네일 최대 4(기본 대체 이미지 치환)·총 수량·주소·밀리초 날짜.

**Independent Test**: 주문 3건 후 조회 → totalCount 3·최신순·카드 필드 전부.

- [x] T017 [US2] [Red] `OrderControllerTest.kt` 에 리스트 케이스 추가 — totalCount·최신순, size 초과 시 hasNext+nextCursor 로 다음 페이지, 음식 5종 주문의 thumbnails 4개 제한, 이미지 없는 음식은 기본 대체 URL(`food_not_found.png` 포함 단정), 주문 없는 회원 totalCount 0·빈 목록, orderedAt 밀리초 long. Red 확인
- [x] T018 [US2] 응답 DTO 신설 `api/src/main/kotlin/com/kbap/api/order/OrderResponses.kt` — `OrderListPage(totalCount·items·hasNext·nextCursor)`·`OrderSummaryResponse(orderId·orderedAt·roadAddress?·totalQuantity·thumbnails)` (스키마 설명 contracts 대로)
- [x] T019 [US2] `OrderService.kt` 에 `getOrderPage(memberId, cursor, size)` — `CursorParser` 재사용(id 커서), 페이지 주문의 항목 일괄 로드(`findByOrderIdInOrderByIdAsc`) → 총 수량 집계·foodId 수집 → `FoodJpaRepository.findAllById` 1회 → `FoodService.resolveImageUrlOrDefault` 로 썸네일 조립(N+1 금지). `@Transactional(readOnly = true)`. 컨트롤러 `@GetMapping` 추가. T017 Green 확인

## Phase 5: US3 — 주문 상세 조회 (P3)

**Goal**: `GET /api/orders/{orderId}` — 리스트 정보 + 항목별 내역 + 총가격, 본인만.

**Independent Test**: 1,000×2 + 3,000×1 주문 상세 → totalPrice 5,000. 타인·부재 404.

- [x] T020 [US3] [Red] `OrderControllerTest.kt` 에 상세 케이스 추가 — 항목별 수량·가격·foodId, totalPrice 계산, price null 항목 합산 제외(전부 null 이면 0), 타인 주문 404 ORDER-002, 부재 404 ORDER-002. Red 확인
- [x] T021 [US3] `OrderResponses.kt` 에 `OrderDetailResponse(요약 필드 + items[]·totalPrice)`·`OrderItemResponse(menuName·quantity·price?·foodId)` 추가, `OrderService.getOrderDetail(memberId, orderId)`(본인 검증 → ORDER_NOT_FOUND 통일) + 컨트롤러 `@GetMapping("/{orderId}")`. T020 Green 확인

## Phase 6: Polish & Cross-Cutting

- [x] T022 전체 회귀 `./gradlew test` (ArchUnit 포함 — order 도메인 방향·seam Spring-free·어댑터 참조 창구 검증)
- [x] T023 [P] `specs/kb-337-order-history/contracts/order-api.md` 최종 대조(구현 ↔ 계약) — 어긋나면 계약 쪽 갱신
- [x] T024 agent-hub 갱신 — `wiki/order-history.md` 신설(스캔당 1회 unique 설계 경위·좌표 미노출·스냅샷 원칙·썸네일 표시 시점 치환·imageRef 기본이미지 저장 금지 분석) + INDEX 한 줄 + `sprint-8-backlog.md` KB-337/351 항목 갱신, 허브 커밋·푸시

## Dependencies & Execution Order

```
Phase 1 (T001)
  └─ Phase 2 (T002·T003 [P] → T004 → T005·T006·T007 [P] → T008 → T009 → T010, T011·T012 [P])
       └─ Phase 3 US1 (T013 → T014 → T015 → T016)
            └─ Phase 4 US2 (T017 → T018 → T019)
                 └─ Phase 5 US3 (T020 → T021)
                      └─ Phase 6 (T022 → T023 [P]·T024)
```

- [P] 후보: T002·T003(다른 테스트 파일), T005·T006·T007(리포/SQL/포트), T011·T012, T023.
- US2·US3 은 US1 의 저장 API 를 테스트 픽스처로 쓰므로 순차가 자연스럽다(같은 `OrderControllerTest` 파일에 케이스 누적).

## Implementation Strategy

- **MVP = Phase 1~3**: 저장만으로 배포 가능(데이터 축적 시작). 조회는 다음 증분.
- 커밋 단위 제안: ① Phase 1–3 (`feat(order): 스캔 메뉴 주문 내역 저장 — 스캔당 1회·역지오코딩`) ② Phase 4–5 (`feat(order): 주문 리스트·상세 조회`) ③ Phase 6 문서.
- 운영 후속(quickstart): Google 콘솔 Geocoding API 사용 설정 + 키 제한 목록 추가 — PR 본문 체크박스로.
