# Implementation Plan: 스캔 응답 DB 매칭 음식명 번역 (KB-189)

**Branch**: `kb-189-scan-name-translation` | **Date**: 2026-07-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-189-scan-name-translation/spec.md`

## Summary

스캔 응답(`ScanResult.ItemRiskResult`)의 `name` 이 항상 비전 추출값(`menu.name`)으로 나가는 버그를 고친다(`ScanService.kt:55`). DB 매칭·READY 음식은 기존 `Food.displayName(lang)`(nameTranslations 기반, `LocalizedText.resolve` 한국어 폴백)을 회원 앱 언어로 조립해 `name` 에 담고, 미매칭·INCOMPLETE 항목은 비전 추출 이름을 유지한다. 회원 언어는 `memberService.getMember(memberId).profile.appLanguage`, 미설정(null)이면 헌법 원칙 V(1)에 따라 `KO` 기본. **프로덕션 변경은 `ScanService` 로직 + `ScanResponse` Swagger 문구 2파일** — 신규 클래스·설정·DB·API 계약(필드 구조) 변경 0. 현 `name` Swagger 설명("비전 인식이 읽은 원문")이 미번역 동작을 명시하고 있어 함께 갱신한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존)

**Primary Dependencies**: Spring Boot 4.1 — 신규 의존 0. 재사용: `Food.displayName(LanguageCode)`, `LocalizedText.resolve`(ko 폴백), `MemberService.getMember`, `LanguageCode`

**Storage**: 변경 없음 — DB 스키마·Flyway·엔티티 0

**Testing**: 기존 `ScanControllerTest`(app:api 통합, MockMvc + MySQL Testcontainers + `FakeMenuBoardVisionExtractor`) 확장. 시드 헬퍼에 회원 appLanguage·음식 name_translations 파라미터 추가

**Target Platform**: `:app:api` (스캔 응답 경로). `:app:batch` 범위 밖

**Project Type**: 기존 모듈러 모놀리스 — 수정 모듈은 `:domain:scan` 하나

**Performance Goals**: 해당 없음 — `getMember` 조회 1회 추가(스캔 경로는 이미 회원 조회·외부 LLM 호출 포함, 무시 가능)

**Constraints**: 외부 API 계약(필드명·타입·에러코드) 불변 — `name` 값의 의미만 수정. 스캔 이력(`scan_history.menu_name`)은 기존대로 비전 추출 이름 유지

**Scale/Scope**: 프로덕션 1파일 수정(핵심 diff ~3줄), 테스트 1파일 수정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | `ScanControllerTest` 에 번역·미매칭 유지·폴백 시나리오를 먼저 추가해 Red 확인 후 `ScanService` 수정. 기존 매칭 name 단언(`"Kimchi 김치찌개"` → `"김치찌개"`) 갱신도 Red 에 포함 |
| II. Bounded Contexts | ✅ | 신규 도메인 의존 0 — `ScanService` 는 이미 `FoodService`·`MemberService` 를 단방향 의존(scan → food → member, Gradle 선언 기존). 엔티티 도메인 메서드(`displayName`)만 추가 사용 |
| III. Layered Dependency Direction | ✅ | 의존 방향 변경 0. 컨트롤러·application 계층 무변경 |
| IV. Persistence Encapsulation | ✅ | 리포지토리·엔티티 접근 변경 0 — 도메인 서비스 창구(`getMember`)만 사용 |
| V. Domain Content Language Policy | ✅ | 사전 번역 DB 데이터(`nameTranslations`)를 그대로 사용. 미설정 → `KO` 기본(V-1), 번역 부재 → `ko` 폴백(V-2) 모두 기존 `LocalizedText.resolve` 규칙. 비지원 코드 fail-fast(V-3)는 프로필 저장 시점에 이미 강제돼 이 경로에 유입 불가 |

Post-Phase 1 재평가: 위반 없음(신규 설계 요소 자체가 없음).

## Project Structure

### Documentation (this feature)

```text
specs/kb-189-scan-name-translation/
├── spec.md              # 기능 명세
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 설계 결정 3건
├── quickstart.md        # 검증 방법
└── tasks.md             # /speckit-tasks 산출물(이 커맨드는 생성 안 함)
```

data-model.md·contracts/ 없음 — 엔티티·스키마·API 계약(필드 구조) 변경이 0 이다(`name` 값 의미만 변경).

### Source Code (repository root)

```text
domain/scan/src/main/kotlin/com/kbap/domain/scan/
└── ScanService.kt                    # [수정] 회원 언어 조회 + 매칭 항목 name 번역 조립

app/api/src/main/kotlin/com/kbap/app/api/scan/
└── ScanResponse.kt                   # [수정] name 필드 Swagger 설명 갱신 — 현재 "비전 인식이 읽은 원문"으로
                                      #   미번역 동작을 명시 중이라 새 의미(매칭=회원 앱 언어 번역명, 미매칭=사진 원문)로 교체

app/api/src/test/kotlin/com/kbap/app/api/scan/
└── ScanControllerTest.kt             # [수정] 시드 헬퍼 확장 + 번역/유지/폴백 시나리오, 기존 name 단언 갱신
```

**Structure Decision**: 기존 구조 그대로 — 신규 파일 0. 조립 로직은 응답을 만드는 `ScanService.scanMenuBoardImage` 항목 매핑 한 지점에 둔다(응답 조립의 유일 경로이므로 루트 픽스).

## 설계 (Phase 1)

핵심 diff (`ScanService.scanMenuBoardImage`):

```kotlin
val lang = memberService.getMember(memberId).profile.appLanguage ?: LanguageCode.KO
...
val items = extracted.map { menu ->
    val food = foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]
    val matched = food?.isReady() == true
    ScanResult.ItemRiskResult(
        ...
        matched = matched,
        name = if (matched) food!!.displayName(lang) else menu.name,
        ...
    )
}
```

- 번역 대상 = `matched`(READY) 와 동일 조건 — INCOMPLETE 는 번역 데이터가 없어 비전 이름 유지(spec US2, Jira "DB 매칭(READY)").
- `recordHistory` 는 `extracted` 의 `menu.name` 을 쓰므로 이력 저장은 자동으로 불변(FR-005).
- `ScanResult` DTO·컨트롤러·`ScanResponse` 무변경.

## Complexity Tracking

위반 없음 — 해당 없음.
