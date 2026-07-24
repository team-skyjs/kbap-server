# Research: KB-191 프로필 응답 provider 필드

Technical Context 에 NEEDS CLARIFICATION 없음 — 저장 구조·노출 값·변경 지점이 모두 기존 코드로 확정된다. 결정 사항만 기록한다.

## Decision 1: DTO 필드 타입 — `String`(enum 이름)

- **Decision**: `MyProfileResult.provider`·`MyProfileResponse.provider` 를 `String` 으로 두고 `member.provider.name` 을 매핑한다.
- **Rationale**: 헌법 Additional Constraints("도메인 모델을 API 응답으로 그대로 노출하지 않는다")와 기존 선례(`countryCode = member.profile.countryCode?.name`, `appLanguage = …?.code`) 준수. 직렬화 결과는 enum 을 담아도 동일하지만, 도메인 enum 이 응답 계층 시그니처에 새지 않는다.
- **Alternatives considered**: `SocialProvider` enum 을 DTO 에 그대로 노출 — 직렬화 결과 동일하나 도메인 타입이 `:app:api` 응답 계약에 결합돼 기각.

## Decision 2: nullability — non-null `String`

- **Decision**: 응답 필드는 non-null.
- **Rationale**: `Member.provider` 가 `nullable = false`(ENUM 'GOOGLE','APPLE') — 값 없는 회원이 구조적으로 불가.
- **Alternatives considered**: 방어적 nullable — 실제 불가능한 상태를 계약에 노출하므로 기각.

## Decision 3: 테스트 표면 — 기존 `MemberControllerTest` 통합 테스트에 검증 추가

- **Decision**: 기존 `getMyProfile` 응답 검증 테스트에 `provider` assertion 을 추가(Red)하고, 필요 시 APPLE 케이스는 저장값 검증으로 커버한다. 신규 테스트 클래스 없음.
- **Rationale**: Jira DoD 가 명시(`MemberControllerTest에 provider 응답 검증`). 응답 조립 한 줄짜리 변경이라 단위 테스트 분리는 과잉.
- **Alternatives considered**: `MyProfileResult` 단위 테스트 신설 — 통합 테스트가 동일 경로를 이미 관통하므로 기각(YAGNI).
