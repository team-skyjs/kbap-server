# Phase 0 Research: 메뉴 목록 조회 (무한 스크롤, no-offset)

모든 항목은 `develop`(c55c42c) 코드베이스 근거로 해소했다. NEEDS CLARIFICATION 잔여 없음.

## R1. 페이지네이션 — no-offset(keyset) vs 기존 offset

- **Decision**: `WHERE (:cursor IS NULL OR f.id < :cursor) ORDER BY f.id DESC` + `LIMIT size+1` 로 keyset 구현. 커서 = 직전 페이지 마지막 항목 foodId.
- **Rationale**: 기존 `FoodScoringSource.nextChunk(page,size)` 는 `PageRequest.of(page,size)` **offset** 방식이라(배치 전량 소진용) 뒤 페이지에서 OFFSET 스캔 비용이 커지고, 스크롤 중 삽입/삭제 시 중복·누락이 생긴다 — 스펙 요구(FR-003·FR-004)와 배치된다. keyset 은 PK 역방향 range scan 이라 깊이 무관 상수 성능(SC-003)이고, 경계 밖 삽입/삭제에 안정적(SC-002).
- **Alternatives considered**: (a) offset `Pageable` 재사용 — 깊은 페이지 저하·중복/누락으로 기각. (b) 별도 정렬 컬럼(created_at) 커서 — 동시각 tie-break 위해 (created_at,id) 복합 커서 필요·불필요한 복잡도라 기각. PK `id` 단독이 유일·단조라 최신순 정렬 키로 충분.

## R2. 컬렉션 fetch-join + 페이지네이션 안전성

- **Decision**: 2단계 조회 — ① `select f.id ... where id<cursor order by id desc`(PageRequest(0,size+1), 컬렉션 미조인) → ② `select distinct f join fetch f.foodAvoidanceSubstances where f.id in :ids order by f.id desc`.
- **Rationale**: 컬렉션 `join fetch` + `LIMIT` 을 한 쿼리에 쓰면 Hibernate 가 **인메모리 페이징**(HHH000104)으로 떨어져 위험. 기존 상세/스코어링 경로가 이미 이 2단계 패턴(`findFoodIds` → `findByIdInWithAvoidanceSubstances`)을 쓰므로 그대로 계승하되 ①을 keyset 으로 교체. `size+1`(21) 조회로 hasNext 판정, 초과분 버리고 20 반환, `nextCursor = items.last().foodId`.
- **Alternatives**: `@EntityGraph` 단일 쿼리 — 컬렉션+limit 동일 문제라 기각. `@BatchSize` — N+1 완화지만 라운드트립 증가라 2단계가 우월.

## R3. 커서 표현 — 평문 숫자 vs 불투명(opaque) 인코딩

- **Decision**: 커서를 **평문 숫자(마지막 foodId)** 로 노출. 요청 `cursor`(숫자), 응답 `nextCursor`(**Long?**, 끝이면 null). 공유 봉투 `Page<T>(payload, hasNext, nextCursor: Long?)` 의 `nextCursor` 필드로 그대로 전달(문자열 변환 없음).
- **Rationale**: foodId 는 이미 각 항목에 노출되는 식별자라 커서로 재노출해도 추가 누수 없음(KISS). 숫자 그대로 두면 직렬화·파싱이 단순하고 `Page<T>` 를 모든 keyset 목록 API 가 재사용한다. Base64/HMAC 인코딩은 정렬 키가 단일 id 인 현 설계에서 이득 없음.
- **Alternatives**: 불투명 토큰 — 정렬 키가 복합/민감해질 때만 가치. 현재 과설계라 기각(향후 검색이 복합 커서 필요 시 seam 에서 재검토).

## R4. 항목 위험도(overallRiskStatus) 계산 — 상세와 동일 의미, 페이지 일괄

- **Decision**: 페이지의 20개 food 에 대해 회피 조달(`AvoidedSubstanceProvider.avoidedCodes()`) 1회 + 카탈로그 일괄 조회(`AvoidanceSubstanceRepository.findByCodes(페이지 전체 성분코드 합집합)`) 1회로, food 별 `food.overallRisk(avoidedCodes ∩ 카탈로그존재코드)` 를 인메모리 계산.
- **Rationale**: 상세(`GetFoodDetailUseCase`)가 `avoidedCodes ∩ resolvableCodes`(소프트삭제 카탈로그 제외)로 종합 위험도를 내므로 목록도 동일 의미로 맞춘다(스키마·의미 일관, FR-006/FR-008). 페이지 단위 1 카탈로그 쿼리로 N+1 회피. 상세와 달리 성분 표시명은 카드에 불필요하므로 이름 해석은 생략(위험도만).
- **Alternatives**: food 별 카탈로그 조회 — N+1 이라 기각. 카탈로그 필터 없이 전체 성분으로 계산 — 소프트삭제 성분이 위험도에 반영돼 상세와 불일치, 기각.
- **Note**: 회피 목록은 현재 `MockAvoidedSubstanceProvider`(SOY·MILK·PEANUT·SHRIMP·EGG). 익명 브라우즈라 실사용자 회피 컨텍스트는 후속(로그인/회피 프로필)에서 대체. 목록은 상세와 **같은 provider 빈**을 주입해 동작 동일.

## R5. 언어 처리 — 원칙 V 재사용

- **Decision**: `LanguageResolver.resolve(lang)`(=`LanguageCode.from`) 재사용. 미지정/번역부재 → ko 폴백, 미지원 코드 → 400(지원목록 안내, 상세와 동일 경로). 표시명 = `food.displayName(lang)`.
- **Rationale**: 원칙 V·spec 008 을 상세와 동일하게 충족해 목록/상세 언어 동작을 일치시킨다. 별도 로직 없음.

## R6. 잘못된 커서·빈 결과 에러 매핑

- **Decision**: 파싱 불가/음수 커서 → 400 실패. 빈 결과 → 200 + `items:[]`·`hasNext:false`·`nextCursor:null`.
- **Rationale**: `GlobalExceptionHandler` 가 `IllegalArgumentException`·`MeogoException`(ErrorCode.status) 을 이미 400 으로 매핑. 잘못된 커서는 `IllegalArgumentException`(컨트롤러 `require`) 또는 신규 `FoodErrorCode.INVALID_CURSOR(400)` 로 던져 `BaseResponse.fail(message)` 반환. 빈 결과는 정상 흐름(FR-010).
- **Alternatives**: 잘못된 커서를 조용히 첫 페이지로 폴백 — 디버깅 저해·원칙 V 의 fail-fast 정신과 불일치라 기각(명시적 실패 선호).

## R7. 상세 연결(foodId) 의존 — KB-98

- **Decision**: 본 태스크는 항목에 foodId 를 담기만 하고 **상세 엔드포인트는 손대지 않는다**. 상세를 foodId 조회로 정합하는 작업은 **KB-98**(에픽 '메뉴 조회 기능', 현재 스프린트)로 분리.
- **Rationale**: 사용자가 항목 식별자를 foodId 로 확정(‘둘 다’ 아님)했고, 상세 조회 방식 변경은 응답 스키마·기존 테스트·menuName 조회 폐기 방침까지 얽혀 독립 태스크가 적절. 클릭-스루 end-to-end 완결은 KB-98 이 담당.
- **Impact**: KB-98 머지 전까지 목록의 foodId 로 상세를 곧장 호출할 수는 없다(상세는 아직 menuName). 목록 자체(스크롤·페이지·요약·위험도)는 독립적으로 완결·검증 가능(US1/US2/US3).

## R8. 엔드포인트 경로

- **Decision**: `GET /api/v1/foods` (컬렉션 루트, query `cursor`·`lang`). 상세는 기존 `GET /api/v1/foods/detail` 유지.
- **Rationale**: `ApiPaths.V1` 상수 + 컬렉션 루트가 REST 관례에 맞고 상세 경로와 충돌 없음. 규약(`/api/v` 시작)·`BaseResponse` 봉투 준수.
- **Alternatives**: `/api/v1/foods/list` — 동사형이라 REST 관례상 열등, 기각.
