# Quickstart: 프로필 부분 수정 (KB-124)

## 건드리는 파일

**신규 (1)**
- `application/client/.../member/dto/ProfileUpdateInput.kt` — 전 필드 nullable (`null` = 미전송)

**수정 (3)**
- `app/api/.../member/ProfileUpdateRequest.kt` — 전 필드 nullable + 기본값 `null`, `toInput()` → `ProfileUpdateInput`
- `app/api/.../member/MemberApi.kt` — `PATCH /me/profile` 설명·예시를 부분 수정 계약으로 갱신(빈 배열 vs 미전송 명시)
- `application/client/.../member/MemberProfileUseCase.kt` — `update(ProfileUpdateInput)` 이 전달된 필드만 검증해 기존 프로필과 병합. 기존 `validatedProfile()` 을 필드 단위 검증 함수 4개로 분해해 온보딩과 공유

**무변경**: `OnboardingRequest`·`MemberProfileInput`·`core:member`(도메인)·`infra:persistence`·Flyway·`MemberController`(`update(request.toInput(memberId))` 호출 형태 그대로).

## 테스트 (Red 먼저)

| 파일 | 성격 | 시나리오 |
|---|---|---|
| `application/client/src/test/.../member/MemberProfileUseCaseTest.kt` | 단위·페이크 | **부분 수정**: 닉네임·국가·언어만 → 기피 성분 유지 / 기피 성분만 → 나머지 유지 / 기피 성분 `[]` → 전부 해제 / 아무 필드도 없음 → 프로필 불변 / 전달된 국가 코드만 무효 → 거절 + 프로필 불변 / 맵기 선호도 보존. **기존 온보딩 시나리오는 그대로 green** |
| `app/api/src/test/.../member/MemberControllerTest.kt` | Testcontainers+MockMvc | 닉네임·국가·언어만 보낸 뒤 프로필 조회 시 기피 성분 유지 / 기피 성분만 보낸 뒤 닉네임·국가·언어 유지 / `{}` → 200·무변경 / `avoidanceSubstanceCodes: []` → 전부 해제 / 전달된 값만 무효면 400 |

검증은 `GET /api/v1/members/me/profile` 응답(`MyProfileResponse.avoidanceSubstanceCodes` 등)으로 한다 — 기피 성분은 DB 컬럼이 아니라 `profile` JSON 안에 있어 조회 API 가 가장 정직한 관측점이다.

**회귀 가드**: 기존 온보딩 테스트(전 필드 필수·무효 입력 400·재제출 400)가 하나도 깨지지 않아야 한다(FR-007·SC-005).

## 검증

```bash
./gradlew :application:client:test --tests "*MemberProfileUseCaseTest*"
./gradlew :app:api:test --tests "*MemberControllerTest*"
./gradlew build
```
