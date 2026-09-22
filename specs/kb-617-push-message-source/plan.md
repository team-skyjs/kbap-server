# Implementation Plan: 푸시 알림 문구를 메시지 파일로 이관

**Branch**: `kb-617-push-message-source` | **Date**: 2026-09-22 (구현 완료·범위 확장 반영) | **Spec**: [spec.md](spec.md) | **Jira**: [KB-617](https://simhani1.atlassian.net/browse/KB-617)

**Input**: Feature specification from `/specs/kb-617-push-message-source/spec.md`

## Summary

`PushTemplates` 코틀린 맵 상수(유형 5 × 언어 10, 스캔 제안 슬롯 2 × 언어 10, 수신거부 안내 10)를 `:common` 의 언어별 properties 파일 10개로 옮기고, `PushMessageRenderer` 가 Spring `MessageSource` 에서 문구를 읽게 한다. `MessageSource` 빈은 `:common` 에 설정 클래스 하나로 두고 api 는 컴포넌트 스캔, batch 는 `PushConfig` 의 `@Import` 로 같은 빈을 쓴다. 치환·광고 접두·수신거부 안내·절단 로직은 렌더러에 그대로 남는다. **범위 확장(2026-09-22 사용자 결정)**: 문구를 Codex 제안 + 윤문 검수 후보 3개씩으로 교체하고(이모지 포함), 렌더러가 후보를 무작위 선택한다. 수신거부 안내는 기존 문구 고정. `PushTemplates` 삭제.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (`spring-context` 의 `ResourceBundleMessageSource`) — 신규 의존성 없음

**Storage**: N/A (classpath 리소스 `common/src/main/resources/messages/push_<lang>.properties`)

**Testing**: Kotest `BehaviorSpec` — 렌더러·MessageSource 는 Spring 컨텍스트 없이 직접 구성해 검증(새 컨텍스트 금지 규약), 기존 `PushDispatchServiceTest`(common)·`ScanSuggestionPushJobTest`(batch) 통합 테스트가 양쪽 컨텍스트 조립을 검증

**Target Platform**: `:api`·`:batch` bootJar (공유 `:common`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스)

**Performance Goals**: 없음 — `ResourceBundleMessageSource` 는 번들을 JVM 내 캐시하므로 렌더당 맵 조회 수준(현재와 동급)

**Constraints**: 렌더 규칙(치환·광고 접두·수신거부·절단) 불변 / 후보 수 언어 간 동일 / 누락 언어는 폴백 없이 예외 / 서버 시스템 로케일 무관 / UTF-8

**Scale/Scope**: 언어당 문구 키 39개(유형 4×3후보×2 + NEWS 1×2 + 슬롯 2×3후보×2 + 수신거부 1) × 언어 10 = 390항목·파일 10개, 프로덕션 코드 변경 4파일(+삭제 1), 테스트 변경 3파일

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 렌더러 테스트를 먼저 MessageSource 주입형으로 고쳐 Red(컴파일/키 누락) → 파일·구현으로 Green. 누락 검증·zh 구분·UTF-8(이모지) 테스트를 구현 전에 작성 |
| II. Bounded Contexts | PASS | 변경은 `common.domain.notification` 내부 + 공유 vocabulary `LanguageCode`(`common.domain` 루트)에 `locale` 프로퍼티 추가뿐. 도메인 간 의존 방향 추가 없음 |
| III. Layered Dependency | PASS | 설정·리소스 모두 `:common` — api·batch → common 단방향 유지. batch 는 기존 `PushConfig` `@Import` 패턴 재사용(스캔 범위 불변) |
| IV. Persistence Ownership | N/A | 엔티티·리포지토리·스키마 변경 없음 (Flyway 없음) |
| V. Content Language Policy | PASS | 푸시 문구는 "정적 UI 문구" 계열 — 음식 콘텐츠 번역 정책(ko 폴백·en 폴백)과 분리. 누락 시 폴백 없이 예외(현재 동작 유지, spec FR-008) |

게이트 통과 — 위반 없음. (Phase 1 설계 후 재평가: 동일, 변동 없음)

## Project Structure

### Documentation (this feature)

```text
specs/kb-617-push-message-source/
├── plan.md
├── research.md
├── data-model.md        # 메시지 키 스키마·파일 규칙
├── quickstart.md        # 검증 절차
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출 (여기서 만들지 않음)
```

contracts/ 는 만들지 않는다 — 외부 인터페이스(HTTP·이벤트) 변경이 없고 `PushMessageRenderer.render` 시그니처도 불변이다.

### Source Code (repository root)

```text
common/src/main/resources/messages/          # 신규 디렉터리
├── push_ko.properties
├── push_zh_Hans.properties
├── push_en.properties
├── push_ja.properties
├── push_zh_Hant.properties
├── push_vi.properties
├── push_id.properties
├── push_th.properties
├── push_ru.properties
└── push_es.properties                        # 기본 파일(push.properties)은 두지 않는다 — 폴백 차단

common/src/main/kotlin/com/kbap/common/domain/
├── LanguageCode.kt                           # 수정: val locale: Locale = Locale.forLanguageTag(code)
└── notification/
    ├── PushMessageSourceConfig.kt            # 신규: MessageSource 빈 (UTF-8, 시스템 로케일 폴백 off)
    ├── PushMessageRenderer.kt                # 수정: MessageSource 주입, 키 조립
    └── PushTemplates.kt                      # 삭제

batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt   # 수정: @Import 에 PushMessageSourceConfig 추가

common/src/test/kotlin/com/kbap/common/domain/notification/
├── PushMessageRendererTest.kt                # 수정: MessageSource 직접 구성, 누락·zh·랜덤 선택·동작 고정
└── PushDispatchServiceTest.kt                # 수정: 정확 문자열 → 언어 차이·{food} 치환 검증
batch/src/test/kotlin/com/kbap/batch/notification/suggestion/ScanSuggestionPushJobTest.kt  # 수정: PushTemplates 참조 → 리터럴·슬롯 키워드
```

**Structure Decision**: 설정 클래스는 렌더러 옆(`common.domain.notification`)에 둔다 — api 는 `com.kbap` 루트 스캔, common 테스트는 `CommonTestApp` 의 도메인 패키지 스캔으로 자동 등록되고, batch 만 기존 `@Import` 목록에 한 줄 추가한다. api·batch yml 은 건드리지 않는다(`spring.messages.*` 자동구성에 의존하지 않으므로 두 앱의 설정이 어긋날 여지가 없다).

## 설계 요점

1. **빈 정의** (`PushMessageSourceConfig`): 빈 이름 `messageSource`(Spring 표준 이름 — Boot 자동구성은 물러나고, `MessageSource` 타입 주입이 모호하지 않다). `ResourceBundleMessageSource` — `basename = "messages/push"`, `defaultEncoding = UTF-8`, `fallbackToSystemLocale = false`, `useCodeAsDefaultMessage` 기본값(false) 유지.
2. **키 조립** (렌더러): `push.<type>.<n>.title|body`, `push.<type>.<slot>.<n>.title|body`, `push.opt-out` — `<type>`·`<slot>` 은 enum name 소문자, `<n>` 은 1부터의 후보 번호. 후보 수는 `<n>.title` 을 1부터 느슨 조회(`getMessage(code, null, null, locale)`)해 끊기는 곳까지 센다. 슬롯 접두에 후보가 0개면 유형 접두로 떨어진다. 선택은 생성자 주입 `pickVariant: (count) -> index`(기본 `Random.nextInt`) — 테스트는 고정 선택기를 넣는다. 고른 번호의 title·body·수신거부 키는 엄격 조회로 누락 시 `NoSuchMessageException`.
3. **치환은 기존 정규식 유지**: 인자 배열을 넘기지 않으면(`args = null`) `MessageSource` 는 `MessageFormat` 을 거치지 않고 원문을 그대로 돌려준다 → `{food}` 이름 자리표시자·따옴표가 변형되지 않고, 렌더러의 `fill()` 이 지금처럼 치환한다. MessageFormat 의 위치 인자로 바꾸지 않는다.
4. **Locale 매핑**: `Locale.forLanguageTag(code)`. JDK 21 실측 — `zh-Hans`→`push_zh_Hans`, `zh-Hant`→`push_zh_Hant`, `id`→`push_id`(구 코드 `in` 아님), 파일 없는 언어→실패.
5. **정합 검증**: 문구가 교체됐으므로 구 상수와의 대조는 없다. 영구 테스트는 (a) 유형(·슬롯) × 언어 후보 수 동일 + 각 후보 title/body 존재, (b) 선택기 계약(후보 수 전달·같은 번호 쌍), (c) 대표 문구 정확 일치(ko 슬롯 1번·zh 간/번체 수신거부)만 둔다. 통합 테스트(dispatch·batch)는 무작위라 정확 문자열 대신 언어 차이·`{food}` 치환·접두/안내로 검증한다.

## Complexity Tracking

위반 없음 — 해당 없음.
