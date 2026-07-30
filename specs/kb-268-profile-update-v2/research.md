# Research: kb-268 프로필 수정 v2

Technical Context 에 NEEDS CLARIFICATION 은 없다. 설계 판단이 필요한 지점만 정리한다.

## R1. v2 를 별도 컨트롤러로 둘 것인가, 기존 컨트롤러에 매핑만 추가할 것인가

- **Decision**: 별도 클래스 `MemberV2Controller`(`@RequestMapping(ApiPaths.V2 + "/members")`) 를 신설한다.
- **Rationale**: CLAUDE.md 경로 규약이 "같은 리소스의 v1·v2 컨트롤러는 서로 다른 베이스를 써 공존한다"로 고정. 기존 `MemberController` 는 클래스 레벨 `@RequestMapping(ApiPaths.V1 + "/members")` 라 v2 매핑을 섞으면 베이스 상수 하드코딩이 생긴다.
- **Alternatives considered**: 기존 컨트롤러에 절대경로 `@PatchMapping("/api/v2/members/me/profile")` 추가 — `ApiPaths` 단일 출처 규약 위반이라 기각.

## R2. v2 에서 국적 변경 불가를 어떻게 보장하나

- **Decision**: 요청 DTO(`ProfileUpdateV2Request`)에서 `countryCode` 필드를 제거하고, `ProfileUpdateInput` 변환 시 `countryCode = null` 로 고정한다. 서비스·도메인은 변경하지 않는다.
- **Rationale**: 기존 `MemberProfile.updatedWith` 는 null 을 "변경 없음"으로 처리하는 부분 수정 의미론이라, null 고정만으로 "어떤 요청으로도 국적 불변"이 성립한다. 클라이언트가 JSON 에 `countryCode` 를 끼워 보내도 Spring Boot 기본 Jackson 설정(`FAIL_ON_UNKNOWN_PROPERTIES` 비활성)이 무지 필드를 무시하므로 스펙의 "오류가 아니라 무시" 요구와 일치한다.
- **Alternatives considered**: (1) 서비스에 v2 전용 `updateProfileWithoutCountry` 추가 — 동일 로직 중복이라 기각. (2) `countryCode` 포함 요청을 400 거절 — 클라이언트 실수에 과민하고 Jackson 기본 동작을 거꾸로 뒤집는 설정이 필요해 기각(스펙 Edge Case 도 "무시"로 확정).

## R3. 인증 필터 경로 등록

- **Decision**: `WebConfig.jwtAuthenticationFilterRegistration` 의 `addUrlPatterns` 에 `"${ApiPaths.V2}/members/*"` 를 추가한다.
- **Rationale**: JwtAuthenticationFilter 는 URL 패턴 명시 등록 방식이라 v2 경로를 빠뜨리면 **인증 없이 통과**된다(리뷰 함정 목록의 "신규 보호 경로 필터 등록" 항목). `@AuthMemberId` 리졸버가 401 을 내긴 하나 필터 계층 방어를 v1 과 동일하게 유지한다.
- **Alternatives considered**: `"/api/*"` 광역 등록 — 비보호 경로(auth 로그인 등)까지 걸려 기각.

## R4. ArchUnit·기존 테스트 영향

- **Decision**: 추가 조치 없음(확인만).
- **Rationale**: `ModuleBoundaryTest` 는 컨트롤러 매핑이 `/api/v` 로 시작하는지 검사 — `/api/v2` 는 통과. 시드·마이그레이션 결합 테스트 무관(스키마 불변).
