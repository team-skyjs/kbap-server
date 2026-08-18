# Research: 리뷰 태그 가능 음식 목록 조회 API

## R1. 엔드포인트 위치·경로

- **Decision**: `GET /api/foods/scanned` — `FoodController` 에 추가, `@AuthMemberId`(필수 인증).
- **Rationale**: 응답이 음식 요약 목록(`Page<FoodSummaryResponse>`)이라 food 기능의 목록 변형이다. `/api/foods/search` 가 `/{foodId}` 와 공존하는 선례 그대로(리터럴 경로 우선). 리뷰 기능에 종속시키면(`/api/reviews/...`) 향후 다른 화면(재스캔 유도 등)에서 재사용할 때 이름이 거짓이 된다.
- **Alternatives considered**: `GET /api/scans/foods` — 스캔 이력 화면용 API 로 오독될 소지. `GET /api/reviews/taggable-foods` — 리뷰 종속.

## R2. 단일 엔드포인트 + keyword 옵션

- **Decision**: keyword 파라미터는 **옵션** — 없으면 전체(스캔 음식) 목록, 있으면 음식명 필터. 별도 `/scanned/search` 경로를 만들지 않는다.
- **Rationale**: 기존 browse/search 분리는 전체 음식이 대상이라 의미가 갈렸지만, 여기는 "내 스캔 음식"이라는 한 목록의 필터일 뿐이다. 매칭 규칙은 기존 검색 native 쿼리(display_name collate like + `json_extract(name_translations, :jsonPath)`)를 그대로 복제한다.
- **Alternatives considered**: browse/search 두 경로 미러링 — 경로·DTO·스웨거 2벌에 실익 없음.

## R3. 중복 제거 + 최신 스캔순 페이징 쿼리 — (2026-08-19 폐기: R9)

- **Decision**: `ScanHistoryJpaRepository` 에 native 쿼리 추가 — 파생 테이블로 `group by sh.food_id` + `max(sh.created_at) as last_scanned_at` 을 만들고 `(last_scanned_at, food_id)` 내림차순 keyset 으로 `limit :size`. join 은 `findRecentReadyFoodIds` 와 동일(`food.status='ACTIVE' and content_status='READY'`, `sh.status='ACTIVE'`).
- **Rationale**: 기존 `findRecentReadyFoodIds` 의 규칙(중복 제거·최신순·READY 만)을 페이징 가능한 형태로 확장한 것 — 홈 recentScans 와 규칙 일치(FR/가정). 집계 컬럼 keyset 은 파생 테이블 바깥 where 로 거는 것이 명확하다.
- **Alternatives considered**: JPQL — group by + json 함수 + collate 조합이 native 가 이미 선례(searchFoodPageIds). offset 페이징 — 프로젝트 전체가 no-offset 규약.

## R4. 커서 계약 — 기존 Long 커서 유지 — (2026-08-19 폐기: R9)

- **Decision**: `nextCursor` = 마지막 항목의 foodId(Long, 기존 `CursorParser`·`Page` 계약 그대로). 커서 수신 시 서버가 그 음식의 본인 기준 `max(created_at)` 를 보조 쿼리로 재계산해 `(last_scanned_at, food_id)` keyset 에 넣는다.
- **Rationale**: 정렬키가 집계값(last_scanned_at)이라 커서에 시각을 실으려면 복합/불투명 커서가 필요해지는데, 클라이언트 계약을 기존 Long 커서와 다르게 만드는 비용이 더 크다. 보조 쿼리 1회는 PK 급 조회라 무시 가능.
- **경계**: 커서 foodId 의 스캔 이력이 사라진 경우(탈퇴 등 소프트삭제) 재계산이 불가 → 기존 비정상 커서와 동일하게 400 처리. 커서 유효 중 새 스캔으로 그 음식이 앞으로 당겨지면 항목이 중복/누락될 수 있으나 keyset 페이징의 일반 특성으로 감수(기존 목록들과 동일 수위).
- **Alternatives considered**: `"epochMillis_foodId"` 복합 문자열 커서 — 이 API 만 커서 형식이 달라짐. 기각.

## R5. 조립 위치·응답 재사용

- **Decision**: `ScanService` 에 페이지 조회 메서드를 추가해(스캔 이력이 원천) ids 페이지를 얻고, `FoodService.getReadyFoodsByIds` 로 로드 후 **ids 순서 보존 재정렬**(HomeService 의 associateBy+mapNotNull 패턴), 기존 `FoodSummaryView` 매핑·`FoodController.toPage`(북마크·평점 일괄 조회)를 재사용한다.
- **Rationale**: 응답 형태 FR-005(기존 음식 요약과 동일)를 코드 재사용으로 보장. 신규 응답 DTO 없음.
- **Alternatives considered**: 전용 응답 DTO — 계약 이중화. 기각.

## R6. 보호 경로·버전

- **Decision**: `WebConfig` JWT 보호 경로에 `/api/foods/scanned` 를 **정확 패턴으로 추가**(foods 하위 나머지는 비회원 공개 유지). `X-API-Version` 은 기존 무버전 매핑(1.0 기본) — 신규 엔드포인트라 버전 분기 없음.
- **Rationale**: 신규 보호 경로 등록 누락은 두 번 밟은 함정([[review-domain-pitfalls]]) — 전 시나리오 401 로 깨진다. 반대로 이 API 는 등록해야 401 이 계약이 된다(FR-004).

## R8. (개정 2026-08-19) 별도 경로 → 기존 검색 엔드포인트 `scope` 파라미터 통합

- **Decision**: `GET /api/foods/scanned` 를 없애고 `GET /api/foods/search` 에 `scope=all|scanned`(기본 all) 파라미터로 통합한다. 분기는 `FoodService.searchFoodPage` 안 조건문이 소유한다.
- **Rationale**: 클라이언트 검색 화면이 탭(일반/태그) 하나의 UI 라 단일 엔드포인트가 자연스럽고, `GET /api/reviews` 의 foodId 파라미터 통합(#144) 선례와 정합. 파라미터 이름은 UI 용어(tab)·모호어(type) 대신 검색 범위를 뜻하는 `scope`, 값은 데이터 의미인 `scanned`.
- **비용(감수)**: scope=scanned 의 회원 전용 보호를 URL 필터로 못 건다(필터는 파라미터를 모름) — 컨트롤러 분기가 401(AUTH-003, 필터와 동일 코드)을 소유한다. 커서 의미(등록순 vs 최신 스캔순)가 scope 에 따라 갈리는 점은 문서·테스트로 고정한다.
- **(재개정 2026-08-19)** keyword 는 scope 무관 **필수**로 통일 — 이 API 는 검색 전용이고, 검색어 입력 전 초기 화면(스캔 목록)은 별도의 스캔 내역 조회가 담당하기로 클라이언트 플로우가 확정됐다. 이로써 두 scope 의 계약 차이는 인증(401)과 정렬·커서 의미만 남는다. 초기 화면용 스캔 내역 조회 GET API 는 현재 없음(홈 recentScans 동봉뿐) — 별도 태스크 필요.
- **Alternatives considered**: `tab=food|tag` — UI 용어가 API 에 새어 화면 개편 시 이름이 거짓이 됨. `type` — 무엇의 타입인지 모호. boolean `scannedOnly` — 제3 범위(북마크 등) 확장 시 재설계.

## R10. (2026-08-19) 초기 화면용 스캔 음식 목록 API 부활 — GET /api/foods/scanned

- **Decision**: 검색어 입력 전 초기 화면은 홈 recentScans(10개 고정 동봉) 재사용 대신 **독립 목록 API** 로 제공한다 — `GET /api/foods/scanned`(회원 전용·JWT 보호 경로), keyword 없음, 커서 페이징(20건). R3/R4 의 복합 keyset·커서 재계산 설계를 이 API 가 그대로 사용한다(검색 API 에서는 R9 로 폐기, 목록 API 에서는 응답 상한·무한스크롤이 목적이라 유효).
- **Rationale**: 태그 화면 진입마다 홈 전체 페이로드를 받는 건 어색하고, 목록은 검색과 달리 keyword 로 모수가 좁혀지지 않아 전체 반환 시 상한이 없다.

## R9. (재개정 2026-08-19) scanned 검색 페이징 폐기 — 매칭 전체 단일 응답

- **Decision**: scope=scanned 는 페이징하지 않는다 — 매칭 전체를 한 번에 반환(응답 봉투는 Page 유지, hasNext 항상 false·nextCursor 항상 null·cursor 무시). R3 의 복합 keyset·R4 의 커서 재계산은 폐기하고 쿼리를 단순 group by + 정렬로 되돌린다.
- **Rationale**: 정렬키가 집계값(max(created_at))이라 커서가 있어도 파생 집계는 매 페이지 전체 재계산된다 — 커서가 DB 비용을 줄이지 못한다. keyword 필수화(R8 재개정)로 결과 모수도 검색어로 좁혀진 내 스캔 음식뿐이라 단일 응답이 소박하다.
- **감수**: 응답 크기 상한이 없다 — 헤비 유저의 스캔 음식이 커지면 페이지당 북마크·평점 집계 비용도 함께 커진다. 실측 병목이 되면 (member_id, food_id) 요약 테이블 + 단순 keyset 으로 최적화(R7 경로와 동일).

## R7. 스키마·인덱스

- **Decision**: 마이그레이션 없음. 기존 인덱스 `idx_scan_history_recent(member_id, created_at)` 로 회원 필터 후 집계 — 스캔 이력 규모(회원당 수십~수백 건)에서 충분하다.
- **Rationale**: 읽기 전용 기능. 인덱스 추가는 측정된 병목이 생길 때.
