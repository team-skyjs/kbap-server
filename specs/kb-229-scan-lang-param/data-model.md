# Data Model: kb-229-scan-lang-param

DB 스키마 변경 없음(Flyway 마이그레이션 0건) — 변경은 도메인 모델·JSON 직렬화 형태에 국한된다.

## MemberProfile (값 객체 — `:domain:member`)

| 속성 | 변경 | 비고 |
|------|------|------|
| nickname / avoidanceSubstanceCodes / spicinessPreference / countryCode / profileImageUrl | 유지 | |
| `appLanguage: LanguageCode?` | **제거** | 마지막 사용처(ScanService)가 요청 파라미터로 전환되어 소멸. `validatedLanguage` 헬퍼도 함께 제거 |

- `of(...)`·`updatedWith(...)` 시그니처에서 `appLanguage` 파라미터 제거.
- `Member.completeOnboarding(...)`·`Member.updateProfile(...)` 파라미터에서도 제거.

## MemberProfileJson (JSON 직렬화 형태 — `member.profile` 컬럼)

| 키 | 변경 | 비고 |
|------|------|------|
| avoidanceSubstanceCodes / spicinessPreference / countryCode / profileImageUrl | 유지 | |
| `appLanguage` | **제거(쓰기 중단)** | 기존 row 의 legacy 키는 **무시하고 읽는다** — `@JsonIgnoreProperties(ignoreUnknown = true)` 명시(research R3). 마이그레이션 없음 |

- 상태 전이: 신규 저장분부터 `appLanguage` 키가 빠진 JSON 이 쓰인다. legacy 키가 있는 row 도 수정 시 다시 저장되면서 자연 소멸한다(강제 정리 없음).

## ScanLangRequest (신규 — `:app:api` 요청 DTO)

| 속성 | 타입 | 규칙 |
|------|------|------|
| `lang` | `String` | `@field:NotBlank` — 누락·빈 값·공백이면 400(원칙 V). 컨트롤러가 `LanguageCode.from(lang)` 으로 확정(미지원 코드 → `EN`) |

## ScanService 입력 (도메인 서비스 계약)

- `scanMenuBoardImage(memberId: Long, imagePath: String, ocrItems: List<OcrItem>, lang: LanguageCode)` — `lang` 파라미터 추가.
- 내부에서 회원 프로필 언어 조회 제거. 번역 부재 시 `ko` 폴백은 기존 `Food.displayName(lang)` 동작 그대로.

## 제거되는 DTO 필드 (경계 계약)

| 위치 | 필드 |
|------|------|
| `OnboardingRequest` / `MemberProfileInput` | `appLanguage: String` (필수 → 소멸) |
| `ProfileUpdateRequest` / `ProfileUpdateInput` | `appLanguage: String?` |
| `MyProfileResponse` / `MyProfileResult` | `appLanguage: String?` |
