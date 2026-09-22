# Research: 푸시 문구 MessageSource 이관

spec 체크리스트가 plan 으로 넘긴 기술 쟁점 4건 + 부수 결정.

## R1. 이름 있는 자리표시자 `{food}` vs MessageFormat

- **Decision**: `MessageSource.getMessage(code, null, locale)` 로 **인자 없이** 원문만 읽고, 치환은 렌더러의 기존 정규식(`\{(\w+)}`)을 그대로 쓴다.
- **Rationale**: `AbstractMessageSource` 는 `alwaysUseMessageFormat=false`(기본)이고 인자가 비어 있으면 `MessageFormat` 을 거치지 않고 원문을 반환한다. 따라서 `{food}` 가 "잘못된 인자 인덱스"로 터지지 않고, 작은따옴표 이스케이프(`''`) 규칙도 적용되지 않는다 → 문구를 글자 그대로 옮길 수 있고, "없는 인자는 빈 문자열" 동작도 보존된다.
- **Alternatives**: (a) `{0}` 위치 인자로 전환 — 문구 전부 재작성, 번역 담당자에게 `{0}` 이 `{food}` 보다 불친절, NEWS 의 `{title}`/`{body}` 다중 인자 순서 관리 필요. 기각. (b) ICU MessageFormat(이름 인자 지원) — 신규 의존성. 기각.

## R2. 누락 언어 폴백 차단

- **Decision**: 기본 파일(`push.properties`)을 **두지 않고** `fallbackToSystemLocale = false`.
- **Rationale**: `ResourceBundleMessageSource` 의 조회 순서는 요청 로케일 → (옵션) 시스템 로케일 → 기본 파일. 둘 다 끊으면 누락 키·누락 언어는 `NoSuchMessageException` — 현재 `getValue` 예외 동작과 동급(FR-008). JDK 21 실측으로 파일 없는 `th` 가 다른 언어로 새지 않고 실패함을 확인.
- **Alternatives**: `push.properties` 에 영어를 두고 폴백 — 헌법 V 의 en 폴백은 **음식 콘텐츠·표시 언어 파라미터** 정책이지 푸시 문구가 아니다. `LanguageCode` 는 이미 10종으로 확정된 값이라 폴백할 상황 자체가 "번역 누락 버그"뿐이고, 그건 조용히 넘기지 말고 테스트에서 터져야 한다. 기각.

## R3. `LanguageCode` → `Locale` 매핑 (zh-Hans / zh-Hant)

- **Decision**: `LanguageCode.locale = Locale.forLanguageTag(code)`. 파일명은 JDK 번들 규칙대로 `push_zh_Hans.properties`·`push_zh_Hant.properties`(스크립트 서브태그는 `_` 뒤 Title-case).
- **Rationale**: `code` 가 이미 BCP 47 언어 태그다. 별도 매핑 테이블이 필요 없다. JDK 21.0.11 실측: `zh-Hans`→`push_zh_Hans`, `zh-Hant`→`push_zh_Hant`, `id`→`push_id`. (`id` 는 JDK 17+ 에서 구 코드 `in` 으로 바뀌지 않는다.)
- **Alternatives**: `Locale.SIMPLIFIED_CHINESE`(zh_CN)/`TRADITIONAL_CHINESE`(zh_TW) 상수 — 지역 기반이라 `zh-Hans` 코드와 의미가 어긋나고 파일명이 `zh_CN` 이 된다. 기각.

## R4. api·batch 동일 구성

- **Decision**: `:common` 의 `com.kbap.common.domain.notification.PushMessageSourceConfig` 에 `@Bean fun messageSource(): MessageSource` 하나. api 는 루트 스캔으로, batch 는 `PushConfig` 의 `@Import(...)` 목록에 추가.
- **Rationale**: batch 는 스캔을 `com.kbap.batch` + `common.infra.llm` 으로 좁혀 두고 push 부품을 `PushConfig` 가 명시 import 하는 기존 패턴이 있다(`PushMessageRenderer` 도 거기 있다). 빈 정의가 코드 한 곳이라 두 앱의 기준이 어긋날 수 없다. Boot 자동구성(`spring.messages.basename` yml)에 기대면 api·batch·테스트 yml 3곳에 같은 값을 중복해야 하고, 자동구성은 기본 파일(`messages.properties`) 존재를 조건으로 걸어 R2 와 충돌한다.
- **빈 이름 `messageSource`**: Spring 표준 이름이라 컨텍스트가 이 빈을 자신의 MessageSource 로 채택한다 → `MessageSource` 타입 주입이 유일하게 해석된다(다른 이름으로 두면 컨텍스트 기본 `DelegatingMessageSource` 와 타입이 겹쳐 `@Qualifier` 가 필요). 현재 코드베이스에 다른 MessageSource·`messages*.properties` 사용처가 없어(grep 확인) 가로챌 기존 동작이 없다. 향후 다른 문구 묶음이 생기면 같은 빈에 basename 을 추가한다.

## R5. UTF-8·이모지

- **Decision**: `setDefaultEncoding("UTF-8")`. 검증은 테스트 전용 번들 `messages/push-fixture_ko.properties`(이모지 🍚·ASCII 작은따옴표·`>` 포함)를 설정 빈 인스턴스에 `addBasenames` 로 얹어 읽는다.
- **Rationale**: properties 파일의 역사적 기본 인코딩은 ISO-8859-1 이라 명시가 필요하다. 운영 문구엔 이모지가 없으므로(spec Assumptions) 테스트 전용 문구로 확인하되, **프로덕션 설정 빈을 그대로** 써서 인코딩 설정이 실제로 검증되게 한다(프로덕션 코드에 테스트용 훅 추가 없음). Gradle `processResources` 는 바이트 복사라 인코딩 설정 불필요.
- **Alternatives**: `\uXXXX` 이스케이프 — 번역 담당자가 읽고 고칠 수 없다. 이관 목적에 반함. 기각.

## R6. 키 네이밍

- **Decision**: `push.<type>.title`, `push.<type>.body`, `push.<type>.<slot>.title|body`, `push.opt-out`. `<type>`/`<slot>` = enum name 소문자.
- **Rationale**: enum name 에서 기계적으로 유도돼 매핑 테이블이 없고, 누락 검증 테스트가 `entries` 순회로 전 키를 생성할 수 있다. enum 이름 변경은 컴파일이 아니라 누락 테스트가 잡는다.
