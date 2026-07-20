# Research: 스캔 응답 DB 매칭 음식명 번역 (KB-189)

Technical Context 에 NEEDS CLARIFICATION 없음 — 결정 3건만 기록한다.

## 1. 번역 조립 위치

- **Decision**: `ScanService.scanMenuBoardImage` 의 항목 매핑(현 `ScanService.kt:47-59`) 한 지점에서 조립한다.
- **Rationale**: 스캔 응답 `name` 을 만드는 유일한 경로다. 여기 고치면 컨트롤러·DTO·이력 저장이 전부 자동으로 올바른 동작을 유지한다(루트 픽스). `Food.displayName(lang)` + `LocalizedText.resolve` 가 이미 번역 선택·ko 폴백을 소유하므로 신규 로직은 조건 분기 한 줄이다.
- **Alternatives considered**: (a) 컨트롤러/응답 DTO 에서 변환 — 도메인 로직(READY 여부·번역)을 web 계층으로 누수, 기각. (b) `ItemRiskResult` 에 별도 `translatedName` 필드 추가 — API 계약 변경이 필요 없고 Jira 가 `name` 값 수정을 명시, 기각.

## 2. 회원 앱 언어 획득과 미설정 기본값

- **Decision**: `memberService.getMember(memberId).profile.appLanguage ?: LanguageCode.KO`.
- **Rationale**: `getMember` 는 기존 public 창구(active 회원, 없으면 예외 — 스캔은 인증 필수라 항상 존재). 미설정 → `KO` 는 헌법 원칙 V(1) "미지정 → ko 기본" 그대로이며, `displayName(KO)` = 한국어 원문이라 폴백 규칙과도 일관된다.
- **Alternatives considered**: (a) `HomeApplicationService` 의 `?: LanguageCode.EN` 관례 — 그쪽은 **게스트(비회원)** 홈 화면 기본값이고, 여기는 인증 회원의 미지정 케이스라 헌법 V(1)의 ko 기본이 우선. (b) 요청 파라미터로 언어 수신 — 회원 설정이 이미 단일 출처, API 계약 변경 불필요, 기각.

## 3. 테스트 위치와 형태

- **Decision**: 기존 `ScanControllerTest`(app:api 통합, MockMvc + Testcontainers + FakeVision) 확장 — 시드 헬퍼에 회원 `profile`(appLanguage)·음식 `name_translations` 파라미터를 추가하고, 번역 응답·미매칭 유지·ko 폴백 3시나리오를 추가한다. 기존 매칭 케이스의 `name` 단언은 새 동작(`"Kimchi 김치찌개"` → `"김치찌개"`)으로 갱신한다.
- **Rationale**: 스캔 응답 조립 경로의 기존 테스트 표면이 정확히 이 파일이다(DoD "스캔 응답 조립 경로 테스트"). `ScanService` 는 의존 5개(internal repo 포함)라 단위 분리는 페이크 4개 신설 비용만 늘고 정보량이 같다.
- **Alternatives considered**: `ScanService` 단위 테스트 신설 — 위 비용으로 기각(통합 테스트가 이미 같은 경로를 검증).
