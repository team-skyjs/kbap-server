# Research: 프로필 사진 필수화 (KB-188)

Technical Context 에 NEEDS CLARIFICATION 없음 — 아래는 구현 방식 결정 4건의 기록이다.

## R1. 온보딩 필수화 강제 수단 — Kotlin non-null 타입 (Bean Validation 아님)

- **Decision**: `OnboardingRequest.profileImageUrl` 을 `String?` → `String`(기본값 없음)으로 바꾼다. 미전송·null 이면 jackson-module-kotlin 이 역직렬화 단계에서 실패하고, 기존 `GlobalExceptionHandler` 의 `HttpMessageNotReadableException` 핸들러가 400 + `COMMON-002` 로 응답한다. `MemberProfileInput.profileImageUrl`·`Member.completeOnboarding` 파라미터도 non-null 로 연쇄 전파한다.
- **Rationale**: KB-158 이 spiciness 필수화에서 확립한 선례("nickname 처럼 non-null 타입 강제")와 동일 — 컴파일러가 null 경로를 구조적으로 소멸시키고, 별도 검증 코드 0줄. Jira 본문도 이 방식을 명시.
- **Alternatives considered**: `@field:NotNull` + Bean Validation → 타입은 nullable 로 남아 도메인까지 `!!`/스마트캐스트 부담이 생기고, 에러 코드도 별도 매핑 필요. 기각.

## R2. 빈 문자열 거부 지점 — `MemberProfile.validatedImagePath` 단일 수정

- **Decision**: `validatedImagePath` 의 `if (trimmed.isEmpty()) return null`(제거 센티널)을 `throw BusinessException(ErrorCode.INVALID_PROFILE_IMAGE_URL)`(MEMBER-008)로 바꾼다. 반환 타입은 `String`(non-null)이 된다. 전체 URL 거부·길이 512 검증은 그대로.
- **Rationale**: 온보딩(`completeOnboarding`→`updatedWith`)과 프로필 수정(`updateProfile`→`updatedWith`)이 이 함수 하나를 공유한다 — 한 곳 수정으로 두 경로가 동시에 빈 문자열을 거부한다(루트 픽스). `updatedWith` 의 사진 3분법(null=유지·값=교체·빈 문자열=제거)이 2분법(null=유지·값=검증 후 교체)으로 단순해진다.
- **Alternatives considered**: 요청 DTO 레벨 검증(`@NotBlank` 등) → 온보딩·수정 두 DTO 에 중복 선언해야 하고 MEMBER-008 코드 매핑도 별도 필요. 도메인 검증 단일 지점이 이미 있으므로 기각.

## R3. 백필 SQL — JSON_SET + 키 부재/JSON null 동시 커버, 소프트 삭제 포함

- **Decision**: Flyway 마이그레이션 1건(점 구분 timestamp 명명):

  ```sql
  UPDATE member
  SET profile = JSON_SET(profile, '$.profileImageUrl', '/images/default/profile/profile-default-512.png')
  WHERE JSON_UNQUOTE(JSON_EXTRACT(profile, '$.profileImageUrl')) IS NULL;
  ```

- **Rationale**: `JSON_UNQUOTE(JSON_EXTRACT(...)) IS NULL` 은 **키 부재 행과 JSON null 행을 모두** 참으로 판정한다(레거시 행은 두 형태가 혼재할 수 있음). WHERE 가드로 이미 값이 있는 행은 절대 건드리지 않는다(FR-005). status 필터를 두지 않아 소프트 삭제 행도 포함한다(스펙 가정 — Flyway 원시 SQL 은 `@SQLRestriction` 미적용이라 자연히 전 행 대상). 단독 UPDATE 1문 — 다른 마이그레이션과 순서 의존 없음(out-of-order 안전), 재실행해도 WHERE 가 걸러 멱등.
- **Alternatives considered**: 애플리케이션 부팅 시 백필 코드 → 1회성 데이터 보정에 상주 코드가 남고 스키마 owner(Flyway) 원칙 위반. 기각. `JSON_REPLACE` → 키 부재 행을 못 채움. 기각.

## R4. 백필 검증 방법 — 테스트가 마이그레이션 리소스를 읽어 직접 실행

- **Decision**: 통합 테스트 yml 은 Flyway off(Testcontainers + Hibernate create)이므로, `MemberControllerTest` 에 백필 시나리오를 추가한다: 회원을 만들고 raw SQL 로 `profileImageUrl` 을 JSON null 로 만든 뒤, **마이그레이션 파일을 클래스패스 리소스로 읽어 그 SQL 을 직접 실행**하고, 조회 응답이 기본 이미지의 완전한 URL 을 노출하는지 검증한다.
- **Rationale**: 마이그레이션 SQL 그 자체를 검증(복사본 SQL 을 테스트에 박으면 드리프트). 기존 `MemberControllerTest` 가 이미 dataSource raw SQL 패턴을 사용 중이라 신규 인프라 0.
- **주의**: CLAUDE.md 의 "시드-동기화 테스트 ↔ 마이그레이션 파일명 결합" 함정 — 리소스 경로를 하드코딩하므로 파일명(버전) 변경 시 테스트 참조를 함께 갱신해야 하며, 못 찾으면 빈 문자열로 읽혀 오진된다. 테스트에서 **리소스 부재 시 명시 실패**(null 체크)를 넣고, given 설명에는 버전 번호를 박지 않는다.
- **Alternatives considered**: 백필 검증 생략(런북만) → 계약의 핵심 축(US3)이라 테스트 커버 필요(헌법 I). 기각. Flyway 를 테스트에서 켜기 → 테스트 스키마 전략(Hibernate create) 전면 전환이라 과잉. 기각.
