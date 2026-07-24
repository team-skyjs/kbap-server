# Implementation Plan: lang 파라미터 정책 통일

**Branch**: `kb-201-home-lang-param` | **Date**: 2026-07-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-201-home-lang-param/spec.md`

## Summary

표시 언어 결정 규칙을 5개 엔드포인트에서 하나로 통일한다.

| 요청 `lang` | 결과 (전 API 공통) |
|---|---|
| 파라미터 없음 · `""` · `"  "` | **400** `COMMON-002` |
| 지원 10종(`ko` 포함)과 정확히 일치 | 그 언어 |
| 그 외 (`fr` · `JA` · `ko-KR` · `" ko "`) | **영어** |

**대상**: `GET /home`(파라미터 신규), `GET /foods`, `GET /foods/search`, `GET /foods/{foodId}`, `GET /bookmarks`

핵심은 **검증을 요청 경계로 올리고 `LanguageCode` 를 순수 lookup 으로 되돌리는 것**이다. 현재 `LanguageCode.from` 은 null 처리·trim·기본값(KO)·예외 발생을 모두 떠안고 있어, 엔드포인트마다 다른 정책이 필요해지는 순간 갈라진다(홈이 그랬다). 정책을 컨트롤러로 내리면 그 마찰이 사라진다.

```kotlin
// :core — 검증 로직 전부 제거, lookup 만 남는다
fun from(code: String): LanguageCode =
    entries.firstOrNull { it.code == code } ?: EN
```

파라미터가 non-null `String` 이라 null 처리도 trim 도 예외도 없다. 그 결과 **`ErrorCode.UNSUPPORTED_LANGUAGE`(`COMMON-001`) 와 `LanguageCode` 의 `BusinessException` 의존이 삭제**된다.

## ⚠️ 파괴적 변경 — 릴리스 조건

**5개 엔드포인트가 동시에 깨진다.**

| 요청 | 변경 전 | 변경 후 |
|---|---|---|
| `GET /home` (현재 앱 전체) | 200 (회원=프로필 언어, 비회원=영어) | **400** |
| `GET /foods` (lang 생략) | 200 (`ko`) | **400** |
| `GET /foods?lang=fr` | 400 `COMMON-001` | **200, 영어** |
| `GET /home?lang=ko` (회원 프로필 `ja`) | 200, 일본어 | **200, 한국어** |

**전제**: 클라이언트 배포 선행 또는 강제 업데이트. 서버 선배포는 불가하다.

미지원 코드가 400→200 으로 **완화**되는 축은 클라이언트를 깨지 않는다. 깨는 것은 **필수화**뿐이다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web, **validation**, data-jpa), springdoc-openapi

**Storage**: MySQL (읽기 전용 — 스키마 변경 없음)

**Testing**: Kotest `BehaviorSpec` + JUnit5 platform, `@SpringBootTest` + MockMvc + MySQL Testcontainers

**Target Platform**: JVM 서버 (`:app:api` bootJar)

**Performance Goals**: 변화 없음 — 파싱이 오히려 단순해진다(trim 제거)

**Constraints**: 비어 있지 않은 `lang` 을 전달한 요청은 값이 무엇이든 실패해서는 안 된다

**Scale/Scope**: 프로덕션 변경 ~12파일(core 2 · domain 4 · application 1 · api 6) + 테스트 6 + 거버넌스 문서 3

## Constitution Check

*GATE: Phase 0 research 전 통과 필요. Phase 1 design 후 재확인.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First (NON-NEGOTIABLE) | ✅ | 모든 task 를 Red → Green → Refactor 로 진행한다. 기존 400 케이스를 EN 폴백 기대로 뒤집는 것부터 Red 로 시작한다. |
| II. Bounded Contexts | ✅ | 도메인 모듈 간 신규 의존 없음. `LanguageCode` 는 이미 `:core` 의 공유 vocabulary 이고, 변경은 `:core` 안에서 끝난다. |
| III. Layered Dependency Direction | ✅ | 의존 방향 그대로. web 검증이 web 계층에 머물러 계층 분리가 선명해진다 — 도메인 서비스가 raw `String?` 파싱 책임을 잃는다. |
| IV. Persistence Encapsulation | ✅ | 영속 코드 변경 0. |
| V. Domain Content Language Policy | ❌ **개정 대상** | clause (3) 이 "지원 목록에 없는 코드 → fail-fast 400 + 지원 목록 안내"를 규정한다. 이 기능은 그것을 **영어 폴백으로 교체**한다. 이탈이 아니라 **원칙 자체를 고친다** — 아래 참조. |

### 원칙 V 개정 (이 기능의 산출물)

이번 변경은 예외를 두는 것이 아니라 원칙을 바꾸는 것이므로, 헌법 개정을 **task 로 포함**한다.

- **개정 내용**: clause (3) "미지원 코드 → 400 fail-fast + 지원 목록 안내" → "미지원 코드 → 영어 폴백". clause (1)(미지정 → ko)은 필수화로 대체돼 함께 정리한다. clause (2)(번역 부재 → ko 폴백)와 "정확 일치·정규화 금지"는 **유지**한다.
- **버전**: MAJOR(비호환 재정의) + Sync Impact Report 갱신.
- **근거**: 기존 근거는 "잘못된 언어 코드가 **조용히 한국어로** 응답되면 오인·디버깅을 어렵게 한다"였다. 영어 폴백은 외국인 대상 서비스에서 성격이 다르며, 무엇보다 `lang` 이 **기기 설정에서 흘러드는 값**이라 서비스가 통제할 수 없다. 미지원 기기 언어 사용자에게 400 을 주면 화면이 열리지 않는다.
- **감수하는 비용**: 클라이언트 오타(`jp`)·대소문자 오류(`EN`)가 200 영어로 조용히 나가 QA 에서 드러나지 않는다. `lang` 필수화가 이에 대한 부분적 방어다(값을 안 보내는 실수는 여전히 시끄럽게 실패).
- **동반 처리**: spec 008(`008-unsupported-language-error`) supersede, **ADR-0013** 신규 작성.

**Post-Phase 1 재확인**: ✅ 개정 후 설계는 원칙 V 와 일치한다. 나머지 원칙 판정 불변.

## Project Structure

### Documentation (this feature)

```text
specs/kb-201-home-lang-param/
├── plan.md              # 이 파일
├── spec.md
├── research.md          # Phase 0 — 검증 계층·필수화·EN 폴백·거버넌스 결정
├── quickstart.md        # Phase 1 — 수동 검증 절차
├── contracts/
│   └── lang-parameter.md  # Phase 1 — 5개 엔드포인트 공통 lang 계약
├── checklists/
│   └── requirements.md
└── tasks.md             # /speckit-tasks 산출
```

`data-model.md` 는 만들지 않는다 — 엔티티·스키마·영속 모델 변경이 없다.

### Source Code (repository root)

```text
core/src/main/kotlin/com/kbap/core/
├── lang/LanguageCode.kt                    # (수정) from(String): 순수 lookup, EN 폴백. trim·예외 제거
└── error/ErrorCode.kt                      # (수정) UNSUPPORTED_LANGUAGE 삭제

domain/food/src/main/kotlin/com/kbap/domain/food/
├── FoodService.kt                          # (수정) LanguageCode.from 호출 제거 — 확정값 수신
└── dto/{BrowseFoodsInput,SearchFoodsInput,GetFoodDetailInput}.kt  # (수정) lang: String? → LanguageCode

domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/
└── BookmarkService.kt                      # (수정) getBookmarkPage(lang: LanguageCode)

application/src/main/kotlin/com/kbap/application/home/
└── HomeApplicationService.kt               # (수정) getHome(memberId, lang: LanguageCode) — 프로필 참조 제거

app/api/src/main/kotlin/com/kbap/app/api/
├── home/{HomeRequest.kt(신규), HomeController.kt, HomeApi.kt}
├── food/{FoodController.kt, FoodApi.kt}    # (수정) lang 필수 + NotBlank + LanguageCode 확정
└── bookmark/{BookmarkController.kt, BookmarkApi.kt}

core/src/test/.../LanguageCodeTest.kt       # (수정) 400 케이스 9개 → EN 폴백으로 전환
app/api/src/test/.../home/{HomeControllerTest,HomeGuestTest}.kt
app/api/src/test/.../food/FoodSearchControllerTest.kt   # (수정) UNSUPPORTED_LANGUAGE 참조 2곳 제거

.specify/memory/constitution.md             # (수정) 원칙 V 개정 + Sync Impact Report
specs/008-unsupported-language-error/       # (수정) superseded 표기
docs/adr/0013-lang-english-fallback.md      # (신규)
```

**Structure Decision**: 기존 모듈 구조를 그대로 쓴다. **검증과 `LanguageCode` 확정을 전부 web 경계에** 둔다 — 도메인·애플리케이션 서비스는 확정된 `LanguageCode` 를 받고 "요청이 언어를 안 줬을 수도 있다"는 상태를 아예 모른다. 타입(non-null `LanguageCode`)이 그 계약을 강제한다.

컨트롤러의 검증 표현 방식은 두 갈래다 — 홈은 신규 파라미터이므로 `HomeRequest` DTO + `@field:NotBlank`, 기존 3개 컨트롤러(food·bookmark)는 이미 여러 쿼리 파라미터를 개별로 받고 있으므로 같은 형태의 요청 DTO 로 묶어 일관되게 처리한다(research R2).

## Complexity Tracking

> Constitution Check 의 원칙 V 는 **이탈이 아니라 개정**으로 처리하므로 정당화 표가 필요 없다. 개정 근거·비용은 위 "원칙 V 개정" 절에 기록했다.

다만 규모에 대한 판단 하나를 남긴다.

| 결정 | 근거 |
|---|---|
| 홈 단독 티켓으로 쪼개지 않고 5개 엔드포인트를 한 번에 변경 | 5개가 **동시에** 파괴적으로 바뀐다. 쪼개면 클라이언트 파괴적 릴리스가 두 번이 되고, 그 사이 기간에 앱이 어느 계약을 따라야 하는지 모호해진다. 릴리스 결합도가 높아 분리 이득이 없다. 대신 헌법 개정·ADR 을 별도 커밋으로 떼어 리뷰 포인트를 분리한다. |
