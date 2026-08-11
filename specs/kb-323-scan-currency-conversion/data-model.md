# Data Model: 스캔 응답에 회원 통화 환산 정보 제공

**DB 변경 없음** — 신규 테이블·컬럼·Flyway 마이그레이션이 없다. 기존 데이터를 읽어 응답 DTO 에 싣는 것이 전부다.

## 재사용하는 기존 모델 (무변경)

### CurrencyCode (`common/src/main/kotlin/com/kbap/common/domain/CurrencyCode.kt`)

공유 도메인 vocabulary. 47개 ISO 4217 통화.

| 속성 | 타입 | 의미 |
|------|------|------|
| `name` | String (enum 상수) | ISO 4217 통화 코드 — 응답의 `code` 로 노출 |
| `krwPerUnit` | BigDecimal(소수 4자리) | 통화 1단위당 원화 금액(참고용 고정 스냅샷) — 응답의 `krwPerUnit` 로 노출 |
| `label` | String | 개발자 가독용 한국어 이름 — 노출 안 함 |

### Member / MemberProfile (`common.domain.member.model`)

- `Member.currency: String?` (varchar(3) 컬럼, KB-322) → `Member.profile` getter 가 `CurrencyCode.from(currency)` 로 변환.
- 스캔 흐름의 접근 경로: `memberService.getMember(memberId).profile.currency: CurrencyCode?`.
- null 인 경우: 통화 설정 도입 이전 가입자, 통화 미매핑 국가 온보딩 회원.

## 변경하는 내부 결과 타입

### ScanResult (`api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt`)

1.0/2.0 공용 스캔 결과. 필드 1개 추가:

| 필드 | 타입 | 추가/기존 | 의미 |
|------|------|-----------|------|
| `items` | List\<ItemRiskResult\> | 기존 | 항목별 판정 — 무변경 |
| `degraded` | Boolean | 기존 | 무변경 |
| `currency` | CurrencyCode? | **추가** | 스캔 요청 회원의 프로필 통화. 미설정이면 null |

`ItemRiskResult`·`SimilarFood` 는 무변경 — 환율은 응답 수준 값이다(research R2).

## 변경하는 응답 DTO

### ScanV2Response (`api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt`)

최상위 필드 1개 + 중첩 DTO 1개 추가:

| 필드 | 타입 | 추가/기존 | 의미 |
|------|------|-----------|------|
| `degraded` | Boolean | 기존 | 무변경 |
| `results` | List\<ItemRiskResponse\> | 기존 | 무변경 (항목 스키마 불변) |
| `currency` | CurrencyResponse? | **추가** | 회원 통화 환산 정보. 통화 미설정 회원은 null |

신규 중첩 DTO `CurrencyResponse`:

| 필드 | 타입 | 의미 |
|------|------|------|
| `code` | String | ISO 4217 통화 코드 (예: "USD") |
| `krwPerUnit` | BigDecimal | 해당 통화 1단위당 원화 금액. 클라이언트 환산식: 원화가격 ÷ krwPerUnit |

매핑: `ScanV2Response.from(result)` 에서 `result.currency?.let { CurrencyResponse(code = it.name, krwPerUnit = it.krwPerUnit) }`.

### ScanResponse (1.0) — 무변경

`ScanResponse.from(result)` 는 `result.currency` 를 읽지 않는다. 1.0 와이어 계약 불변.

## 검증 규칙

- 입력 검증 변경 없음 — 요청 계약 불변(응답만 확장).
- `currency` 는 "있으면 code·krwPerUnit 둘 다, 없으면 통째로 null" — 중첩 객체 구조가 계약으로 보장.

## 상태 전이

없음 — 읽기 전용 파생 값이다.
