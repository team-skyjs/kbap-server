# Research: v2 스캔 응답 기피성분 겹침 표시

Technical Context 에 NEEDS CLARIFICATION 없음 — 아래는 설계 선택지 검토 결과다.

## R1. 겹침 판정 로직의 소유 위치

- **Decision**: `Food` 엔티티(=도메인 모델) 메서드 `overlappedIngredients(avoidedCodes: Set<String>): List<FoodIngredient>` — READY 가 아니거나 성분 미보유면 빈 목록. 겹친 성분의 `FoodIngredient` 를 그대로 반환해 성분별 위험도(`riskLevel()`)까지 호출부가 얻는다.
- **Rationale**: 동일 데이터(`ingredients`)로 판정하는 기존 `overallRisk(avoidedCodes)` 와 대칭이고, 음식 상세(`FoodService.getDetail`)의 성분별 `riskStatus` 와 같은 코드 경로(`FoodIngredient.riskLevel()`)를 재사용한다. 헌법 IV(도메인 로직은 도메인 모델·서비스 소유) 준수. ScanService 는 조립만 한다.
- **Alternatives considered**: ① `overlappedCodes(): Set<String>` (코드만 반환) — 경고 수준 요구(2026-08-13 사용자 확인)를 충족하려면 호출부가 ingredients 를 다시 뒤져야 함. ② ScanService 인라인 계산 — 도메인 판정이 소비 계층으로 유출, overallRisk 와 비대칭. ③ FoodService 메서드 — 인스턴스 데이터만 쓰는 판정이라 엔티티 메서드가 자연스러움.

## R2. 응답 형태 — 항목별 전체 목록 vs 최상위 목록 + 겹친 코드만

- **Decision**: **각 항목에** 회원 기피성분 전체를 `[{code, overlapped}]` 로 반복 포함.
- **Rationale**: 사용자 요구가 "각 음식 정보와 함께 기피성분을 쭉 깔아주면서 겹침 표시" — 클라이언트가 항목 카드 단위로 조인 없이 바로 렌더링. 메뉴판 항목 수 × ≤81 코드라 응답 크기 부담 미미.
- **Alternatives considered**: ① 최상위에 기피성분 목록 1회 + 항목엔 겹친 코드만 — 응답은 작지만 클라이언트가 항목마다 조인해야 해 요구 취지와 어긋남. ② 겹친 성분만 항목에 포함 — "쭉 깔아주면서" (전체 나열) 요구 미충족.

## R3. 성분 표시명·경고 수준 포함 (2026-08-13 개정 — 사용자 확인)

- **Decision**: 각 기피성분에 `code` + **표시명 `name`**(요청 `lang` 해석, 번역 부재 시 ko) + `overlapped` + **겹친 성분만 `riskLevel`**(SAFE/CAUTION/DANGER, 미겹침은 null)을 포함한다. 표시명은 성분 카탈로그(`Ingredient.displayName(lang)`), 경고 수준은 `FoodIngredient.riskLevel()` — 둘 다 음식 상세(`FoodService.getDetail`)와 동일 코드 경로.
- **Rationale**: 클라이언트가 스캔 결과 화면에서 기피재료명과 경고를 조인 없이 바로 렌더링해야 한다는 사용자 요구. 음식 상세와 같은 규칙(포함 확률 10/60 임계)을 재사용하므로 화면 간 판정 불일치가 원천 차단된다. 카탈로그 조회는 스캔당 `findByCodeIn` 1건(회원 기피성분 집합) 추가로 부담 미미.
- **Alternatives considered**: ① 코드만 반환(초안) — 클라이언트가 카탈로그를 따로 보유·조인해야 해 기각(사용자 확인). ② 경고를 boolean 으로 단순화 — 상세 화면(성분별 위험도 3단계)과 표현이 어긋나 규칙 이원화. ③ 겹침 경고에 별도 임계 신설 — 판정 로직과 다른 규칙을 만들지 않는다는 원칙에 반함.
- **참고 차이**: 음식 상세는 *겹친 성분만* 나열하지만, 스캔은 *기피성분 전체*를 나열하고 겹침 여부를 플래그로 구분한다(요구 취지 — "쭉 깔아주면서 겹치는지 표시").

## R4. 미매칭(matched=false)·degraded 항목 처리

- **Decision**: 기피성분 목록 **빈 목록**(spec 확정). similarFood 가 있어도 유사 음식 성분으로 대체 판정하지 않음.
- **Rationale**: riskLevel=UNKNOWN 과 의미 일관 — "판정 불가"를 목록 부재로 표현. 유사 음식은 '정확 매칭 아님' 주의 표시 대상이라 성분 판정을 얹으면 오신뢰 유발.
- **Alternatives considered**: 전 성분 overlapped=false 로 나열 — "겹치지 않음"(안전 오인)과 "판정 불가"를 구분할 수 없어 기각.

## R5. v1 스캔 응답 영향

- **Decision**: `ScanResult.ItemRiskResult.avoidances` 는 기본값 `emptyList()` 로 추가하되 v1 `ScanResponse` 매핑엔 싣지 않는다.
- **Rationale**: v1·v2 가 `ScanService.scan`·`ScanResult` 를 공유하므로 결과 타입엔 한 번만 추가하고, 응답 노출은 v2 만. FR-006(기존 계약 불변) 충족. 기본값 덕에 기존 테스트 픽스처 무수정.
- **Alternatives considered**: v1 에도 노출 — v1 은 클라이언트 idx 매칭 기반 구계약으로 곧 대체될 경로, 계약 확장 불필요.

## R6. 프로필 없는 회원(게스트) — null 표현 (2026-08-13 사용자 확인)

- **Decision**: 회원 프로필(`Member.profile`)이 null 이면 전 항목 `avoidances = null`. 프로필은 있으나 기피 0개면 빈 목록, 미매칭 항목도 빈 목록.
- **Rationale**: 프로필 없음은 "기피 정보의 주체가 없음"(회원 수준)이라 "나열할 것이 없음"(항목 수준·빈 목록)과 의미가 다르다 — 클라이언트가 온보딩 유도 등 다른 UI 를 그릴 수 있게 구분한다. 기존 `getAvoidedCodes` 는 두 경우를 emptySet 으로 뭉개고 `Member.profile` 은 계산 프로퍼티라 null 이 없으므로, 조립부는 이미 조회하는 `Member` 의 `onboardingCompleted` 플래그로 분기한다.
- **Alternatives considered**: 게스트도 빈 목록 — "기피 없음(안전)"과 "기피 정보 모름"이 구분 안 돼 기각(사용자 지시 — null).

## R7. 목록 정렬

- **Decision**: `IngredientCode` enum 선언 순서(ordinal)로 정렬해 결정적 순서 보장.
- **Rationale**: `getAvoidedCodes` 반환이 Set 이라 순서 비보장 — 테스트·클라이언트 렌더링 안정성을 위해 결정적 순서 필요. enum 선언 순서는 카탈로그 분류 순서와 일치.
- **Alternatives considered**: 정렬 안 함 — 응답 순서가 실행마다 흔들려 스냅샷 비교·QA 어려움.
