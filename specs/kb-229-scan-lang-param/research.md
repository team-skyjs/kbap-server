# Research: kb-229-scan-lang-param

Technical Context 에 NEEDS CLARIFICATION 은 없다. 구현 방식 선택지가 갈리는 지점 5곳을 결정한다.

## R1. 스캔 API 의 lang 수신 방식

- **Decision**: `@Valid @ModelAttribute ScanLangRequest`(필드 `lang: String`, `@field:NotBlank`)를 기존 `@RequestBody ScanRequest` 와 **병행** 선언한다(HomeRequest 와 동일 패턴). 컨트롤러가 `LanguageCode.from(request.lang)` 으로 확정해 서비스에 전달한다.
- **Rationale**: 전 API 가 요청 DTO + `@field:NotBlank` 로 검증하고 `MethodArgumentNotValidException` → `GlobalExceptionHandler` → 400 경로를 공유한다. 스캔만 `@RequestParam` 으로 받으면 (1) 빈 문자열(`lang=`)이 통과해 원칙 V 의 "비어 있으면 400" 을 어기고, (2) `@NotBlank` 를 쓰려면 컨트롤러 클래스 `@Validated` 프록시 + `ConstraintViolationException` 핸들러가 추가로 필요하다. Spring MVC 는 한 핸들러 메서드에서 `@ModelAttribute`(쿼리 바인딩)와 `@RequestBody` 를 함께 지원한다.
- **Alternatives considered**: `@RequestParam lang: String` — 빈 값 통과·검증 경로 이원화로 기각. body(`ScanRequest`) 안에 lang 포함 — 사용자 요구가 명시적으로 request parameter 이고, 표시 언어는 리소스 표현 선택이라 쿼리가 의미상 맞아 기각.

## R2. ScanService 시그니처와 member 의존

- **Decision**: `scanMenuBoardImage(memberId, imagePath, ocrItems, lang: LanguageCode)` 로 파라미터를 추가하고, 내부의 `memberService.getMember(memberId).profile.appLanguage` 조회를 삭제한다. 비전 호출(비용) 전에 회원 존재를 확정하는 `memberService.getMember(memberId)` 호출 자체는 유지한다(기존 주석의 의도 — 존재하지 않는 회원의 요청으로 외부 비전 비용을 태우지 않음).
- **Rationale**: 헌법 원칙 V — 검증은 요청 경계가 소유하고 서비스는 확정값(`LanguageCode`)을 받는다. 존재 확인은 언어와 별개의 가드라 남긴다.
- **Alternatives considered**: 서비스가 `String` 을 받아 자체 해석 — 검증 소유 계층 위반으로 기각. 존재 확인 제거 — 미인증·탈퇴 회원 요청에 비전 비용이 나가는 회귀라 기각.

## R3. 기존 회원 profile JSON 의 legacy `appLanguage` 키 처리

- **Decision**: `MemberProfileJson` 에서 `appLanguage` 필드를 제거하고 클래스에 `@JsonIgnoreProperties(ignoreUnknown = true)` 를 명시한다. 데이터 마이그레이션은 하지 않는다(spec Assumptions). 기존 row 의 `{"appLanguage":"ko",...}` JSON 을 읽는 회귀 테스트를 둔다.
- **Rationale**: `member.profile` 은 `@JdbcTypeCode(SqlTypes.JSON)` 매핑이고, Hibernate 의 Jackson `JsonFormatMapper` 는 **Spring Boot 가 관용 설정한 ObjectMapper 가 아니라 자체 기본 ObjectMapper** 를 쓸 수 있다 — 기본 Jackson 은 unknown property 에 `UnrecognizedPropertyException` 을 던지므로, 필드만 지우면 기존 회원 조회가 전부 깨질 수 있다. 애너테이션 명시가 매퍼 구성과 무관하게 안전하며, 향후 필드 제거에도 재사용된다.
- **Alternatives considered**: `appLanguage` 필드를 남기고 무시 — 죽은 필드가 계약에 남아 제거 목적 미달, 기각. Flyway `JSON_REMOVE` 마이그레이션으로 키 일괄 삭제 — 전 회원 row 쓰기가 필요한데 무시로 충분(harmless key)해 기각.

## R4. 구버전 앱이 보내는 `appLanguage` 값 호환

- **Decision**: 별도 코드 없이 요청 DTO 에서 필드 제거만 한다. Spring Boot 의 web ObjectMapper 는 `FAIL_ON_UNKNOWN_PROPERTIES` 가 기본 비활성이라 body 의 미지의 `appLanguage` 키는 자동 무시된다. 온보딩·프로필 수정 테스트에 "appLanguage 를 포함해 보내도 성공" 시나리오로 고정한다.
- **Rationale**: 프레임워크 기본이 FR-007 을 이미 충족 — 코드 추가는 불필요, 테스트로만 계약을 고정한다.
- **Alternatives considered**: deprecated 필드 유지 후 차기 제거 — 2단계 릴리스 비용 대비 이득 없음(서버는 값을 쓰지 않으므로), 기각.

## R5. 온보딩 입력에서 언어 제거의 계약 영향

- **Decision**: `OnboardingRequest`·`MemberProfileInput`·`Member.completeOnboarding` 에서 `appLanguage`(현재 필수 String)를 제거한다. `ProfileUpdateRequest`·`ProfileUpdateInput`·`Member.updateProfile`·`MemberProfile.updatedWith` 에서 선택 필드를 제거한다. `MyProfileResult`·`MyProfileResponse` 에서 응답 필드를 제거한다. `MemberApi` swagger 예시도 함께 갱신한다.
- **Rationale**: 스펙 FR-005·FR-006 — 프로필은 언어를 보관·노출하지 않는다. 온보딩 필수 입력이 줄어드는 방향의 변경이라 신규 클라이언트는 안 보내면 되고, 구버전은 R4 로 흡수된다.
- **Alternatives considered**: 응답에서만 숨기고 저장 유지 — "서버에 언어 저장 경로 0개"(SC-004) 미달로 기각.
