# Data Model: kb-229-scan-lang-param

DDL 변경 없음 — 변경은 도메인 모델·JSON 직렬화 형태와 폐기 키 제거 데이터 마이그레이션 1건이다.

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
| `appLanguage` | **제거** | 기존 row 의 키는 Flyway `JSON_REMOVE` 마이그레이션으로 삭제한다. 롤링 배포 대비로 `@JsonIgnoreProperties(ignoreUnknown = true)` 도 함께 명시해 키가 남아 있어도 읽는다(research R3) |

- 상태 전이: 마이그레이션 시점에 기존 row 의 키가 일괄 제거되고, 이후 저장분은 키 없는 JSON 으로 쓰인다.

## ScanLangRequest (신규 — `:app:api` 요청 DTO)

| 속성 | 타입 | 규칙 |
|------|------|------|
| `lang` | `String` | `@field:NotBlank` — 누락·빈 값·공백이면 400 `COMMON-002`. 컨트롤러가 지원 코드와 정확히 일치할 때만 `LanguageCode` 로 확정하고, 미지원 코드는 400 `COMMON-002` 으로 거절 |

## ScanService 입력 (도메인 서비스 계약)

- `scanMenuBoardImage(memberId: Long, imagePath: String, ocrItems: List<OcrItem>, lang: LanguageCode)` — `lang` 파라미터 추가(컨트롤러가 확정한 지원 언어만 도달).
- 내부에서 회원 프로필 언어 조회 제거. 번역 부재 시 `ko` 폴백은 기존 `Food.displayName(lang)` 동작 그대로.

## 제거되는 DTO 필드 (경계 계약)

| 위치 | 필드 |
|------|------|
| `OnboardingRequest` / `MemberProfileInput` | `appLanguage: String` (필수 → 소멸) |
| `ProfileUpdateRequest` / `ProfileUpdateInput` | `appLanguage: String?` |
| `MyProfileResponse` / `MyProfileResult` | `appLanguage: String?` |
