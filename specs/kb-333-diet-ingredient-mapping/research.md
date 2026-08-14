# Research: diet 카테고리별 회피 재료 매핑 조회

Phase 0 산출물. Technical Context 에 NEEDS CLARIFICATION 은 없었고, 설계 선택지가 갈리는 지점을 결정으로 고정한다.

## R1. 매핑 표현: enum 상수 vs DB 테이블

- **Decision**: `DietCategory` enum(코드·한국어 표시명·`Set<IngredientCode>`)을 **`com.kbap.api.ingredient`** 에 둔다(사용자 결정으로 별도 diet 패키지 대신 ingredient 기능 패키지에 통합). 신규 테이블·Flyway 마이그레이션을 만들지 않는다.
- **Rationale**:
  - 매핑은 기획 확정 고정 표로 운영 편집·소프트삭제·언어별 콘텐츠가 없다(스펙 Assumptions). 헌법 V 의 "taxonomy DB 단일 출처" 패턴이 지불하게 하는 비용(테이블 2개·시드 마이그레이션·시드-동기화 테스트·리포지토리)이 사 주는 것이 없다.
  - `IngredientCode` 참조라 **컴파일 타임에 존재하지 않는 재료를 가리킬 수 없다** — 숫자 id 하드코딩(시드 재배열에 취약)보다 안전하다.
  - `:common` 배치 기준(헌법 III — "api 밖이 컴파일 의존하는가")을 api 단독 소비라 충족하지 못하므로 `:api` 기능 패키지 소속이다.
- **Alternatives considered**:
  - *DB 테이블(diet_category + diet_category_ingredient) + 시드*: 카테고리 다국어 표시명·운영 편집이 생기면 그때 avoidance 카탈로그(ADR-0008) 패턴으로 승격 — 지금은 기각.
  - *숫자 id 직접 매핑(`Set<Long>`)*: 컴파일 검증 불가, 시드 행 순서 변화에 조용히 깨짐 — 기각.
  - *`common.domain.ingredient.model` 배치*: 회원 diet 저장 기능(미래)이 생겨 배치·타 도메인이 참조할 때 승격하면 된다 — 현재는 YAGNI 로 기각.

## R2. 카테고리 표시명 처리 (헌법 V 예외 정당화)

- **Decision**: enum 이 한국어 표시명(`koreanName`)을 직접 들고 응답에 그대로 내보낸다. 카테고리명 다국어는 범위 밖(클라이언트는 `code` 로 분기).
- **Rationale**: 헌법 V 의 "식별자 enum label 은 런타임 미사용·비권위" 규칙은 **DB 가 콘텐츠 단일 출처일 때** enum 쪽 사본을 비권위로 격하하는 장치다. 카테고리는 DB 출처가 아예 없으므로 enum 표시명이 유일 출처이고, 이를 런타임에 쓰는 것이 모순이 아니다. 번역 리소스가 확보되면 R1 의 승격 경로를 따른다.
- **Alternatives considered**: 표시명 없이 code 만 응답 — 클라이언트가 15종 한국어명을 중복 하드코딩하게 되어 기각.

## R3. 번호표 → 코드 변환과 정합 검증 전략

- **Decision**: 기획 표의 재료 번호는 **시드 SQL(`V2026.07.16.21.38.42__seed_avoidance_catalog.sql`)의 1-based 행 순서**로 해석한다(= AUTO_INCREMENT id). 변환 결과(코드 집합)는 data-model.md 에 박제하고, **`DietCategoryMappingSyncTest`** 가 기획 번호표를 그대로 픽스처로 들고 시드 SQL 을 파싱(기존 `IngredientCatalogSeedSyncTest` 의 regex 방식 재사용)해 번호→코드 해석 결과와 `DietCategory` 매핑이 전 카테고리(15종 전수) 일치함을 검증한다.
- **Rationale**: 표본 검증 완료 — 12=PEANUT·26=WHEAT·37=SHRIMP·51=SEAFOOD·59=BROTH·61=BEEF·66=POULTRY·70=POTATO·77=ASAFOETIDA·78=ALCOHOL·80=COOKING_WINE·81=SULFITES 로 도메인 의미(글루텐 4종에 메밀 제외, 오신채 72~77, 근채류 70~76 등)까지 부합. 시드는 81종이고 매핑 사용 번호는 1~80 범위다. 테스트가 번호표(기획 언어)와 코드 매핑(구현 언어) 사이 드리프트를 배포 전 차단한다(FR-006).
- **주의**: 시드 마이그레이션 파일명이 바뀌면 이 테스트의 리소스 경로도 함께 갱신해야 한다(기존 함정 — 못 찾으면 빈 문자열로 읽혀 데이터 불일치로 오진).
- **Alternatives considered**: DB 조회 기반 통합 테스트만으로 검증 — Testcontainers 없이도 돌 수 있는 단위 검증(빠른 피드백)을 잃어 기각. 두 층 다 둔다(통합 테스트는 응답 id·이름 조립 검증 담당).

## R4. 엔드포인트 형태

- **Decision**: `GET /api/ingredients/diets` 하나 — **기존 `IngredientController` 에 핸들러를 추가**한다(사용자 결정 2026-08-14). 쿼리 파라미터 `lang`(필수). 15종 전체를 한 번에 응답(FR-002). 버전 매핑 없이 기본 핸들러(`X-API-Version` 은 전역 필수 규약이 처리).
- **Rationale**: 클라이언트 화면이 전체 목록을 한 번에 그린다(SC-001). 카테고리별 개별 조회는 요청 15회를 유발할 뿐 — 기각. diet 매핑은 재료 카탈로그를 분류 축으로 묶은 뷰라 ingredient 리소스 하위에 자연스럽게 앉는다. 경로는 `ApiPaths.API` 상수 사용(하드코딩 금지).
- **Alternatives considered**: 독립 리소스 `/api/diets` + 전용 `com.kbap.api.diet` 패키지 — 초기 설계였으나 컨트롤러·패키지 신설 대비 이득이 없어 사용자 결정으로 기각(회원 diet 저장 기능이 생겨 diets 가 독립 리소스로 커지면 그때 분리).

## R5. 인증·경로 보호

- **Decision (2026-08-14 개정)**: JWT 보호 경로에 등록하지 않는다 — 인증 없이 호출하는 공개 API 다(기존 `/api/ingredients` 와 동일).
- **Rationale**: 식단 선택은 **가입 전 온보딩 화면**에서도 이뤄져 비회원이 호출한다(초기 결정 "로그인 후 화면" 전제가 틀려 머지 직후 정정 — PR #164 초기 구현은 보호 경로였다). 공개 근거는 재료 카탈로그와 같은 정적 기준 정보라 노출 위험이 없다는 것.

## R6. 재료 id·이름 해석

- **Decision**: 기존 `IngredientQueryService` 에 `getDietIngredientMappings(lang)` 를 추가하고(별도 서비스 클래스 없음), `IngredientJpaRepository.findAll()` 1회로 전 재료(81행)를 로드해 `code → entity` 맵을 만들고, 카테고리별 `IngredientCode` 집합을 (id, `displayName(lang)`) 목록으로 변환한다. 재료 정렬은 id 오름차순(기존 목록 API 와 동일).
- **Rationale**: 81행 전건 로드는 쿼리 1개로 끝나 N+1 이 없다. `displayName` 이 지원 언어·번역 부재 폴백(ko)을 이미 소유 — 재구현하지 않는다.
- **Alternatives considered**: 카테고리별 `findByCodeIn` 15회 — 불필요한 쿼리 증식으로 기각. 캐싱 — 81행 1쿼리에 캐시는 과잉으로 기각.
