# Phase 0 Research: 음식 상세 조회 foodId 정합

기술 컨텍스트에 NEEDS CLARIFICATION 없음. 아래는 스펙의 열린 결정(경로/파라미터·에러 처리·기존 조회 처리방침)을 코드 근거로 확정한 기록.

## Decision 1 — 엔드포인트 형태

- **Decision**: `GET /api/v1/foods/{foodId}` (path variable, 타입 `Long`). `?lang=` 쿼리 파라미터는 유지.
- **Rationale**: foodId 는 리소스 식별자이므로 REST 상 경로 세그먼트가 자연스럽다. DoD 예시(`GET /api/v1/foods/{foodId}`)와 일치. `ApiPaths.V1 + "/foods"` 베이스에 `/{foodId}` 를 잇는다.
- **Alternatives**: `GET /foods/detail?foodId=` (쿼리 유지) — 기존 `/detail` 경로를 살리지만 식별자 조회에 굳이 서브경로를 둘 이유가 없고 REST 관례에서 벗어난다. 기각.

## Decision 2 — 기존 menuName 조회 처리방침

- **Decision**: **대체(제거)**. `GET /foods/detail?menuName=` 및 `FoodRepository.findByKoreanName` 조회 경로(상세용)를 없앤다.
- **Rationale**: 사용자 지시 "메뉴명 **대신** id" + 스펙 Assumptions. 프로덕션 전·더미 데이터라 하위호환 부담 없음. 병행 유지는 이중 진입점·테스트 이중화로 부채만 늘린다.
- **주의**: `findByKoreanName` 자체는 다른 소비자(예: 스캔 매핑)가 있으면 남긴다 — grep 결과 상세 usecase 외 프로덕션 소비자 없음 확인. 상세 usecase 에서만 호출 제거. 포트 메서드 삭제 여부는 잔여 참조 확인 후 tasks 단계에서 결정(없으면 삭제).

## Decision 3 — 미존재·소프트삭제·형식오류의 HTTP 상태

- **Decision**: 셋 다 **400 (잘못된 요청)**.
  - 미존재/소프트삭제: `foodRepository.findById(id)` 가 null → `throw FoodException(FoodErrorCode.NOT_FOUND)`. `FoodErrorCode.NOT_FOUND` 는 이미 `status=400` → `GlobalExceptionHandler` 가 400 BaseResponse 로 매핑. 신규 코드/매핑 불필요.
  - 소프트삭제: `BaseEntity.@SQLRestriction("status = 'ACTIVE'")` 가 조회에서 DELETED 를 자동 제외 → 조회 결과 null → 위와 동일 경로.
  - 형식오류(비숫자 path var): `@PathVariable foodId: Long` 바인딩 실패 → `MethodArgumentTypeMismatchException`. 현재 미처리 → `GlobalExceptionHandler` 에 핸들러 1개 추가해 400 BaseResponse 로 봉투 일관성 유지.
- **Rationale**: 사용자 지시("없는 경우는 bad request"). 기존 menuName 미수록도 400 이었으므로 시맨틱 연속성 유지. 404 를 신설하지 않아 에러 계약이 단순.
- **Alternatives**: 404(리소스 없음) — REST 원론상 더 적확하나 사용자가 400 명시. 기각.

## Decision 4 — 영속 조회 구현

- **Decision**: 어댑터에서 기존 `findByIdInWithAvoidanceSubstances(ids: List<Long>)` fetch-join 쿼리를 `listOf(id)` 로 재사용하고 `.firstOrNull()?.toDomain()`.
- **Rationale**: 상세는 성분 목록이 필요하므로 fetch-join 이 필수인데 동일 쿼리가 이미 존재(목록 조회용). 단건 전용 쿼리를 새로 만들 이유 없음 — N+1·`LazyInitializationException` 방지 규약도 자동 충족.
- **Alternatives**: 전용 `findByIdWithAvoidanceSubstances(id)` @Query 신설 — 중복. 기각. `JpaRepository.findById` 기본 메서드 — 성분 컬렉션 LAZY 미초기화로 도메인 매핑 시 예외 위험. 기각.
