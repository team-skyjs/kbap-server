# Phase 0 Research: 언어 무관 메뉴명 한국어 항상 포함

NEEDS CLARIFICATION 없음 — 스펙 단계에서 유일한 열린 결정(중복 노출 정책)이 사용자 확정되었다. 아래는 구현 근거 조사 결과.

## R1. 한국어 원문은 이미 로드되는가

- **Decision**: DB·영속·마이그레이션 변경 없이 기존 로드된 값을 재사용한다.
- **Rationale**: `FoodJpaEntity.toDomain()` 이 `LocalizedText(korean = koreanName, translations = resolve(nameTranslations))` 로 복원한다. `foods.korean_name` 은 `NOT NULL length=255`. 즉 `Food.content.name.korean` 은 항상 채워져 있고, 지역화 폴백의 종착지도 이 값이다.
- **Alternatives considered**: 별도 쿼리로 한국어명 재조회 — 불필요(이미 도메인에 존재). 기각.

## R2. 지역화명과 한국어 원문을 어디서 얻나 (seam 위치)

- **Decision**: 도메인 `Food` 에 `koreanName(): String`(= `content.name.korean`) seam 추가. 지역화명은 기존 `displayName(lang)` 유지.
- **Rationale**: 유스케이스가 `food.content.name.korean` 로 내부 구조를 뚫지 않게 캡슐화. 기존 `displayName(lang)`·`description(lang)` 패턴과 대칭. `LanguageCode`/`LocalizedText` 규칙(`resolve`)은 kernel 소유 그대로.
- **Alternatives considered**: 유스케이스에서 `LocalizedText` 직접 접근 — 도메인 캡슐화 약화. 기각.

## R3. "동일하면 null" 규칙을 어느 계층에 두나

- **Decision**: 유스케이스(application)에서 `koreanName = food.koreanName().takeIf { it != localizedName }` 로 계산해 Result DTO 의 `koreanName: String?` 에 담는다. web 응답은 그대로 미러링.
- **Rationale**: 기존 유스케이스가 이미 지역화명을 산출해 Result 를 조립하는 지점이라 응집. 판정에 필요한 지역화명이 같은 스코프에 있어 추가 조회·전달이 없다. 단위 테스트로 직접 검증 가능(원칙 I).
- **Alternatives considered**: web `Response.from()` 에서 null 처리 — 지역화명·한국어명 둘 다 Result 로 올려야 해 계약이 커지고, 규칙이 표현 계층으로 흩어짐. 기각.

## R4. 범위 검토 — 추가 수정 API 유무 (사용자 명시 요청)

- **Decision**: 상세·목록만 수정. 스캔 API 불변.
- **Rationale**: 앱 컨트롤러 3종 중 지역화 메뉴명을 반환하는 것은 상세(`FoodDetailResponse.name`)·목록(`MenuSummaryResponse.name`)뿐. 스캔(`SubmitMenuScanResponse`)은 `scanId·itemId·riskLevel·reason` 만 반환하고 메뉴명은 클라이언트가 `rawMenuName` 으로 제출 → 서버가 지역화·에코하지 않음.
- **Alternatives considered**: 스캔 응답에도 한국어명 부가 — 스캔은 위험도 판정 계약이고 메뉴명 소스가 클라이언트라 무의미. 기각.

## R5. 하위 호환

- **Decision**: 순수 필드 추가(`koreanName: String?`). 요청 파라미터·기존 필드 의미 불변.
- **Rationale**: 기존 클라이언트는 미지 필드를 무시. 기존 지역화명·폴백 응답 값은 회귀 없음(SC-004).
