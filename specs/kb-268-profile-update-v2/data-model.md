# Data Model: kb-268 프로필 수정 v2

**스키마·엔티티 변경 없음.** Flyway 마이그레이션 없음.

## 기존 모델 (불변)

- **Member** (`common.domain.member.model.Member`): `nickname` 컬럼 + `profileJson`(JSON — avoidanceSubstanceCodes·spicinessPreference·countryCode·profileImageUrl). 도메인 메서드 `updateProfile(...)` 은 null 인자를 "변경 없음"으로 처리(`MemberProfile.updatedWith`).
- **ProfileUpdateInput** (`common.domain.member.dto`): 전 필드 nullable 부분 수정 입력. v2 는 이 타입을 그대로 쓰되 `countryCode` 에 항상 null 을 전달한다.

## 신규 타입 (api 모듈, DB 무관)

- **ProfileUpdateV2Request**: `nickname?` · `avoidanceSubstanceCodes?` · `profileImageUrl?` · `spicinessPreference?` — v1 요청에서 `countryCode` 만 제거한 형태. `toInput(memberId)` 가 `ProfileUpdateInput(countryCode = null, ...)` 로 변환.

## 불변식

- v2 경로로는 어떤 요청이 와도 `Member.profileJson.countryCode` 가 바뀌지 않는다 (입력 타입에 필드 부재 + 변환 시 null 고정).
- 국적 설정 경로는 온보딩(`completeOnboarding`) 하나만 남는다 — v1 은 구앱 호환용 잠정 예외.
