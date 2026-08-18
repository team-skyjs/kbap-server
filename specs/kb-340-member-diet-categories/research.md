# Research: 회원 프로필 diet 카테고리 복수 선택

## R1. DietCategory enum 을 `:common` ingredient 도메인으로 승격

- **Decision**: `com.kbap.api.ingredient.DietCategory` → `com.kbap.common.domain.ingredient.model.DietCategory` 로 이동(내용 무변경 — 코드·koreanName·`Set<IngredientCode>` 매핑 그대로). `api.ingredient` 의 diets 조회 API 와 `DietCategoryMappingSyncTest` 는 import 만 갱신.
- **Rationale**: member 도메인(`MemberProfile`)이 참조하게 되므로 api 소속으로 둘 수 없다 — diet 위키에 기록된 승격 조건("회원의 diet 선택 저장 기능이 생기면 :common 으로 승격") 충족. `IngredientCode` 와 같은 ingredient 컨텍스트 소속이 자연스럽고, member→ingredient 방향은 `MemberProfile`→`IngredientCode` 로 이미 허용 맵에 있다(ModuleBoundaryTest 수정 불필요).
- **Alternatives considered**: member 도메인 소속 — 매핑의 주인은 재료 쪽이고 ingredient API 가 역참조하게 돼 기각. 문자열만 저장(enum 미참조) — 미지원 값 검증·컴파일 정합을 잃음.

## R2. 저장 형태 — member 테이블 JSON 컬럼

- **Decision**: `member.diet_categories json NOT NULL DEFAULT (JSON_ARRAY())` 컬럼 추가 — `avoidance_substance_codes` 와 동형(프로필 flatten 컬럼 패턴, KB-297 선례). 엔티티는 `@JdbcTypeCode(SqlTypes.JSON) var dietCategories: List<String>`, 도메인(`MemberProfile`)은 `Set<DietCategory>` 로 노출.
- **Rationale**: 15종 한정 소규모 태그 집합 — 별도 조인 테이블은 조회·수정 전부 프로필 단위인 이 데이터에 과설계. DEFAULT (JSON_ARRAY()) 라 기존 행 백필 불필요, additive 컬럼이라 블루/그린 안전.
- **Alternatives considered**: member_diet 조인 테이블 — diet 로 회원을 역조회할 요구가 없음(그때 가서 정규화). SET/비트마스크 — 가독성·확장성 열세.

## R3. 검증·에러 코드

- **Decision**: `MemberProfile` 이 `validatedDiets(raw: List<String>): Set<DietCategory>` 로 소유 — 미지원 값은 신규 `INVALID_DIET_CATEGORY("MEMBER-011", 400)`. 중복은 Set 변환으로 자연 정규화. 기존 `validatedCodes`(회피 재료)와 같은 자리·같은 패턴.
- **Rationale**: 프로필 값 검증은 전부 `MemberProfile` companion 이 소유하는 기존 구조(닉네임·국가·통화·맵기와 동일). MEMBER-011 은 다음 빈 번호(007 은 결번 아님 확인 — 010 까지 사용).
- **Alternatives considered**: 요청 DTO @Pattern — enum 목록과 이중화되어 드리프트.

## R4. 요청·응답 계약 — 기존 프로필 필드 규칙 그대로

- **Decision**:
  - 온보딩(`OnboardingRequest`→`MemberProfileInput`): `dietCategories: List<String> = emptyList()` — 누락 = 빈 목록(avoidanceSubstanceCodes 와 동일).
  - 프로필 수정(`ProfileUpdateRequest`·`ProfileUpdateNoCountryRequest`→`ProfileUpdateInput`): `dietCategories: List<String>? = null` — **누락 = 기존 유지, 빈 배열 = 전체 해제**(`updatedWith` 의 null-유지 규칙 그대로. 스펙 가정 확정).
  - 조회(`MyProfileResult`→`MyProfileResponse`): `dietCategories: List<String>` — 기존 `avoidanceSubstanceCodes` 필드 불변, diet 는 별도 필드.
- **Rationale**: 세 API 모두 기존 필드들과 완전히 같은 수위 — 클라이언트가 새로 배울 규칙이 없다. 수정 DTO 가 2벌(1.0/1.1)인 함정은 둘 다 추가로 해소.
- **Alternatives considered**: 수정에서 누락=해제 — 기존 필드들(null=유지)과 어긋나 기각.

## R5. 버전·판정 무개입

- **Decision**: additive 필드 — `X-API-Version` 증가 없음(KB-334 선례). `avoidedCodes()`·위험도 판정·스캔·`getAvoidedCodes` 소비처는 일절 손대지 않는다(FR-004 — diet 는 저장·복원 전용 태그).
- **Rationale**: 사용자 확정(2026-08-19) — 회피 재료는 클라이언트 커스텀 영역.
