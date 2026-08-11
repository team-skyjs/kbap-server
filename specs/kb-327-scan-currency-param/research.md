# Research: 스캔 2.0 통화 환산 기준을 currency 요청 파라미터로 전환

**Date**: 2026-08-11 | **Plan**: [plan.md](plan.md)

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 갈림길이 있었던 설계 결정 5건의 기록이다.

## R1. 파라미터 수신 위치 — v2 컨트롤러의 `@RequestParam` (신규 홀더·공유 홀더 배제)

- **Decision**: `ScanV2Controller.scan()` 에 `@RequestParam(required = false) currency: String?` 를 직접 추가한다. `ScanLangRequest` 에 넣지 않고, 신규 요청 홀더도 만들지 않는다.
- **Rationale**: `ScanLangRequest` 는 1.0 컨트롤러(`ScanController`)와 공유되는 홀더다 — 여기 넣으면 1.0 이 응답에 쓰지도 않는 파라미터를 조용히 받는 죽은 계약이 생긴다. 선택 파라미터 1개를 위한 신규 `@ModelAttribute` 홀더는 조각 수만 늘린다(YAGNI).
- **Alternatives considered**: ① `ScanLangRequest` 확장 — 1.0 오염으로 기각. ② `ScanCurrencyRequest` 신규 홀더 — 필드 1개짜리 클래스, 실이득 없어 기각.

## R2. 통화 결정 우선순위 — 파라미터 > 프로필 > 없음(null)

- **Decision**: `requestedCurrency ?: member.profile.currency` — 파라미터가 있으면 프로필을 완전히 무시하고, 없으면 기존 동작(프로필, 그것도 없으면 null) 그대로.
- **Rationale**: Jira KB-327 DoD 문언("파라미터가 회원 프로필의 통화 설정보다 우선", "미전달 시 회원은 프로필 통화로 fallback")을 그대로 옮긴 것. 미전달 시 동작이 도입 전과 완전히 같아 기존 클라이언트가 깨지지 않는다.
- **Alternatives considered**: 파라미터 필수화 — 기존 2.0 클라이언트(파라미터 없이 호출)가 즉시 깨져 기각. 프로필 우선·파라미터 보조 — 요구사항(프로필 설정대로 반환되지 않도록)과 정반대라 기각.

## R3. 잘못된 통화 값 처리 — `MEMBER-010` 400 실패 (lang 식 폴백 배제)

- **Decision**: 지원 목록에 없는 값은 `BusinessException(ErrorCode.INVALID_CURRENCY_CODE)`(`MEMBER-010`, 400)로 실패시킨다. 프로필 통화 변경(`MemberProfile.validatedCurrency`)과 동일 정책·동일 코드.
- **Rationale**: lang 의 en 폴백(헌법 V·ADR-0013)은 "기기 설정에서 흘러드는 통제 불가 값 + 400 이면 화면이 안 열림"이라는 특수 근거 위의 정책이다. currency 는 클라이언트가 명시적으로 고르는 값이라 그 근거가 성립하지 않고, 조용한 폴백은 잘못된 환산 금액 표시(돈 표시 오류)로 이어진다 — 시끄러운 실패가 옳다. 에러 코드 신설 없이 기존 코드 재사용으로 클라이언트 분기도 일관된다.
- **Alternatives considered**: ① en/프로필 폴백 — 오타가 잘못된 금액 표시로 조용히 새어 기각. ② 신규 SCAN-xxx 에러 코드 — 같은 의미의 코드 중복 채번이라 기각.

## R4. 검증 소유 계층 — 요청 경계(컨트롤러) 확정, 서비스는 확정 타입 수신

- **Decision**: `ScanV2Controller` 가 raw 문자열을 `CurrencyCode.from(raw) ?: throw BusinessException(INVALID_CURRENCY_CODE)` 로 확정하고, `ScanService` 시그니처는 `requestedCurrency: CurrencyCode? = null` 로 받는다.
- **Rationale**: 헌법 원칙 V — "외부 입력 유효성 검증은 요청 경계가 소유하고 도메인·애플리케이션 서비스는 확정된 값을 받는다. 타입이 계약을 강제하므로 서비스 안에 방어 코드를 두지 않는다." 같은 컨트롤러가 이미 `LanguageCode.from(langRequest.lang)` 으로 동일 패턴을 쓴다. 부수 효과로 잘못된 값이 비전(LLM) 호출·스캔 횟수 증가 전에 차단된다.
- **Alternatives considered**: 서비스가 raw `String?` 을 받아 내부 검증 — 헌법 V 위반(서비스 방어 코드)이자 시그니처가 계약을 흐려 기각. (초안 plan 이 이 방식이었고 본 재실행에서 정정했다.)

## R5. API 버전 — 번호 올리지 않음 (2.0 매핑 유지)

- **Decision**: `ScanV2Controller` 의 `version = "2.0+"` 매핑을 그대로 두고 새 버전 분기를 만들지 않는다.
- **Rationale**: 선택 파라미터 추가 + 미전달 시 동작 불변은 하위 호환(additive)이다. 버전 번호는 앱 릴리스 마커(경로 규약)라 계약이 깨질 때만 올린다.
- **Alternatives considered**: `2.1+` 분기 신설 — 동작 차이가 없는 버전 분기는 매핑만 늘려 기각.
