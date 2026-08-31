# Research: 스캔 응답에 회원 통화 환산 정보 제공

Technical Context 에 NEEDS CLARIFICATION 항목은 없다. 아래는 설계 확정을 위해 조사·결정한 사항이다.

## R1. 환율 출처 — 기존 `CurrencyCode.krwPerUnit` 고정 스냅샷 재사용

- **Decision**: `common/src/main/kotlin/com/kbap/common/domain/CurrencyCode.kt` 의 `krwPerUnit: BigDecimal`(소수 4자리, 통화 1단위당 원화)을 그대로 응답에 싣는다. 신규 환율 조달 경로를 만들지 않는다.
- **Rationale**: 회원 통화 기능(KB-322)이 47개 ISO 4217 통화 전부에 스냅샷 값을 이미 보유하고, 환산 규약 테스트(`CurrencyRateSnapshotTest` — `price ÷ krwPerUnit`, HALF_UP)까지 존재한다. 사용자 지시가 "코드와 그 값을 포함시키면 된다"로 확정했고, 스캔은 사용자 대기 경로라 외부 호출을 넣을 수 없다(Jira 배경과도 부합). 현재 이 값은 main 소스에서 미사용 상태 — 이번 기능이 첫 소비자다.
- **Alternatives considered**:
  - 외부 환율 API + 캐시(Jira 고려사항): 인프라(seam·어댑터·갱신 스케줄·다중 인스턴스 중복 실행 방어)가 통째로 필요. 사용자 지시로 범위 밖 확정. 시세 정확도가 필요해지면 `krwPerUnit` 공급원만 교체하면 응답 계약은 불변 — 그때 가서.
  - DB 환율 테이블: 운영자 갱신 경로가 없는 지금은 enum 하드코딩과 실질 동일하면서 마이그레이션·조회만 늘어남. 기각.

## R2. 응답 형태 — 응답 수준(top-level) 중첩 객체, 항목별 아님

- **Decision**: `ScanV2Response` 최상위에 `currency: { code: String, krwPerUnit: BigDecimal } | null` 하나를 둔다. 메뉴 항목(`ItemRiskResponse`)에는 아무것도 추가하지 않는다.
- **Rationale**: 환율은 회원당 하나다 — 항목마다 반복하면 중복이고, 서버 항목별 환산(FR-002 금지)을 유도한다. 클라이언트는 `price ÷ krwPerUnit` 을 항목마다 스스로 계산한다.
- **Alternatives considered**:
  - 항목별 `convertedPrice`: 서버 환산 금지(사용자 지시)에 위배. 기각.
  - 평평한 두 필드(`currencyCode`·`currencyRate`): null 처리(둘 다 null 이어야 함)가 계약으로 강제되지 않음. 중첩 객체는 "있으면 둘 다, 없으면 통째로 null"을 구조로 보장. 채택 안 함.

## R3. 환율 값 직렬화 — `krwPerUnit` 이름의 BigDecimal JSON number

- **Decision**: 필드명 `krwPerUnit`, 값은 enum 의 BigDecimal 그대로(JSON number, 소수 4자리). 방향은 "해당 통화 1단위당 원화 금액" — 클라이언트 환산식은 `원화가격 ÷ krwPerUnit`.
- **Rationale**: 이름이 방향을 자기 서술한다(`rate` 는 방향 모호). 값·자릿수는 스냅샷 원본 그대로 내려 클라이언트가 반올림 정책을 소유한다(스펙 FR-002). Jackson 은 BigDecimal 을 자릿수 보존 number 로 직렬화한다.
- **Alternatives considered**: 문자열 직렬화(정밀도 방어) — 소수 4자리는 double 정밀도 내라 과방어. 기각.

## R4. 통화 미설정·KRW 처리

- **Decision**: `member.profile.currency == null` 이면 `currency` 필드 전체를 null 로 내리고 스캔은 정상 성공(FR-003). KRW 회원도 동일 형식으로 `{code: "KRW", krwPerUnit: 1.0000}` 을 내린다.
- **Rationale**: 부분 성공 정책(스펙 User Story 2). KRW 특별 취급(생략)은 클라이언트 분기 하나를 서버 분기 하나로 바꿀 뿐 — 균일 계약이 더 단순하다.

## R5. 조회 비용 — 추가 조회 0건

- **Decision**: `ScanService.scan()` 첫 줄의 기존 `memberService.getMember(memberId)` 호출(현재 반환값 폐기 — 존재 검증 용도)을 `val member = ...` 로 받아 `member.profile.currency` 를 꺼낸다.
- **Rationale**: 회원 row 는 이미 읽고 있다. 성능 목표(스캔 응답 시간 불변, SC-003)가 공짜로 충족된다.

## R6. 1.0 응답 불변 — `ScanResult` 공유에도 안전

- **Decision**: 내부 결과 타입 `ScanResult` 에 `currency: CurrencyCode?` 를 추가하되(1.0/2.0 공용 `scan()` 이 채움), 1.0 의 `ScanResponse.from()` 은 이 필드를 읽지 않는다 — 1.0 와이어 계약 불변.
- **Rationale**: 사용자 지시로 1.0 은 범위 밖(스펙 Assumptions). 내부 타입에 실려도 와이어에 안 나가면 계약 불변이며, 추후 1.0 에도 내리기로 하면 `ScanResponse.from()` 한 줄이면 된다.
