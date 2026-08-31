# Research: 온보딩 재료 81종 이름·이미지 공개 조회 (KB-326)

## R1. S3 이미지 키 패턴 → image_path 시드 값

- **Decision**: `images/webp/<IngredientCode 소문자>.webp` (예: `images/webp/egg.webp`, `images/webp/brazil_nut.webp`). 시드는 81행 나열 대신 단일 파생 UPDATE 로 적재한다: `UPDATE ingredients SET image_path = CONCAT('images/webp/', LOWER(code), '.webp')`.
- **Rationale**: 사용자 제공 S3 콘솔 스크린샷으로 실물 확인 — 파일명이 enum 코드의 소문자 snake_case(`abalone.webp`·`brazil_nut.webp`·`cooking_wine.webp`)와 1:1 매칭, 접두사는 `images/webp/` (사용자 확정). 코드에서 기계적으로 파생 가능하므로 81행 하드코딩은 드리프트만 만든다.
- **Alternatives considered**: 81행 명시 UPDATE(코드↔경로 불일치 재료가 생기면 필요하나 현재 전부 규칙 일치라 기각), 한글 라벨 파일명(실물이 영문 코드 기반이라 기각).

## R2. image_path 컬럼 정의

- **Decision**: `ingredients.image_path VARCHAR(255) NULL`. 엔티티 `Ingredient.imagePath: String? = null`, `@Column(name = "image_path", length = 255)`.
- **Rationale**: FR-006(미매칭 재료가 있어도 목록 실패 금지)이 nullable 을 요구한다. 시드 적재 후엔 81종 전부 채워지지만, 향후 재료 추가 시 이미지 준비 전 상태를 표현해야 한다. 255 는 경로 여유폭(현재 최대 ~30자).
- **Alternatives considered**: NOT NULL + 기본값(신규 재료 추가 흐름을 막아 기각), 별도 이미지 테이블(1:1 속성 하나에 과설계 — 기각).

## R3. 응답 이미지 형태 — 경로 vs 완성 URL

- **Decision**: 완성 공개 URL 로 내려준다 — `ImageUrls.resolve(kbap.storage.public-base-url, imagePath)`.
- **Rationale**: 기존 선례 그대로 — `MemberService`·`FoodService` 가 같은 프로퍼티 + `ImageUrls.resolve` 로 완성 URL 을 응답한다. 클라이언트가 베이스 URL 을 몰라도 된다(SC-004 단일 호출 완결).
- **Alternatives considered**: raw path 반환(클라이언트가 CDN 베이스를 별도 관리해야 해 기각).

## R4. 공개(비인증) 엔드포인트 구현 방식

- **Decision**: `JwtAuthenticationFilter` 의 `addUrlPatterns` 에 새 경로를 **등록하지 않는다**. `guestExemptions` 도 불필요.
- **Rationale**: JWT 필터는 경로 opt-in 방식(`WebConfig.jwtAuthenticationFilterRegistration`) — 미등록 경로는 필터를 타지 않아 그대로 공개다. 잘못된 토큰이 와도 검사 자체가 없어 FR-003·엣지케이스(무효 토큰 동반 호출)를 자동 충족한다. `RequestLoggingFilter` 는 `/api/*` 전역이라 로깅은 유지된다.
- **Alternatives considered**: guestExemption 추가(보호 경로에 등록된 패턴의 예외 장치 — 애초에 등록하지 않으면 불필요, 기각).

## R5. 표시 언어(lang) 처리

- **Decision**: `lang` 쿼리 파라미터 **필수**(빈 값 400). `LanguageCode.from(lang)` — 미지원 코드는 `EN` 폴백, 번역 부재는 `Ingredient.displayName(lang)`(내부 `LocalizedText`)의 `ko` 폴백 재사용.
- **Rationale**: 헌법 원칙 V 그대로 — 표시 언어를 받는 API 는 lang 필수, 검증은 요청 경계 소유, 서비스는 확정된 `LanguageCode` 를 받는다. `HomeController` 가 동일 패턴(`LanguageCode.from(request.lang)`)의 선례.
- **Alternatives considered**: lang 생략 시 ko 기본(헌법 V 가 명시 금지 — 기각), Accept-Language 헤더(기존 API 전부 쿼리 파라미터라 일관성 훼손 — 기각).

## R6. 코드 배치 — 도메인 서비스 없이 api 기능 패키지

- **Decision**: `com.kbap.api.ingredient` 기능 패키지에 컨트롤러(`IngredientController` + swagger `IngredientApi`)·API 서비스(`IngredientQueryService`)·응답 DTO 를 함께 둔다. `IngredientJpaRepository.findAll` 직접 사용(도메인 서비스 신설 없음), 트랜잭션 경계는 API 서비스가 `@Transactional(readOnly = true)` 로 소유.
- **Rationale**: 소비자가 api 하나뿐인 단순 목록 조회라 도메인 로직이 없다 — 원칙 IV(위임 전용 창구 서비스 금지, 단순 영속은 소비 계층이 리포지토리 직접). 단 컨트롤러의 리포지토리 직접 호출은 금지 규율이라 API 서비스가 경유한다. `HomeService` 가 동일하게 `IngredientJpaRepository` 를 직접 쓰는 선례.
- **Alternatives considered**: `common.domain.ingredient` 도메인 서비스 신설(비즈니스 로직이 없어 창구 서비스가 됨 — 원칙 IV 위배로 기각).

## R7. 목록 정렬

- **Decision**: `id` 오름차순(시드 삽입 순서 = enum 선언 순서) 고정 정렬.
- **Rationale**: 스펙 가정(안정 정렬이면 충분). 시드가 enum 순서로 INSERT 되어 카테고리 유사군(유제품→견과→해산물…)이 자연히 붙어 있다.
- **Alternatives considered**: 이름 가나다순(언어별로 순서가 달라져 기각).

## R8. OpenAPI 스냅샷

- **Decision**: 새 엔드포인트 추가로 `OpenApiSnapshotTest` 의 스냅샷 갱신이 필요하다 — 구현 단계에서 스냅샷 재생성 절차(테스트 파일의 갱신 방식)를 따른다.
- **Rationale**: develop 최신(371ee4c8, #147)에 OpenAPI 스냅샷 테스트가 존재 — 엔드포인트 추가 시 스냅샷 불일치로 실패한다.
