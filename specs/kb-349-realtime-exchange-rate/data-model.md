# Data Model: KB-349 실시간 환율

영속 스키마 변경 없음. enum·매핑·값 UPDATE 와 seam 하나뿐이다. 환율은 저장하지 않는다.

## `CurrencyCode` (공유 vocabulary, `common.domain`)

| 항목 | 전 | 후 |
|---|---|---|
| 항목 수 | 46 | **30** (frankfurter 지원 전부 + KRW 포함) |
| 필드 | `label`, `krwPerUnit: BigDecimal` | `label` 만 — **환율 필드 삭제** |
| `from(code)` | 정확 일치 lookup, 없으면 null | 동일 (폐기 코드는 null → 프로필 수정 `INVALID_CURRENCY_CODE`) |

변동: 폐기 18 `AED BDT BHD BND EGP FJD JOD KHR KWD KZT MNT NPR PKR QAR RUB SAR TWD VND` · 추가 2 `ISK RON`.

## `CountryCode.currency` (국가→통화 매핑, 197개 전수)

- 폐기 통화를 가리키던 18개국 → `USD`
- `IS → ISK`, `RO → RON`
- 나머지 불변. 컴파일러가 전수 강제.

## `member.currency` (DB 값, 스키마 불변)

- `varchar(3) NULL` 그대로.
- 1회성 UPDATE: `currency IN (폐기 18종)` → `'USD'`. `IS`/`RO` 회원의 기존 `USD` 는 건드리지 않는다.
- 불변 조건: 마이그레이션 후 `member.currency` 의 모든 non-null 값 ∈ 새 `CurrencyCode` 30종.

## seam `ExchangeRateClient` (`common.port.exchange`)

```
fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal?
```
- 반환: 해당 통화 1단위당 원화, HALF_UP 4자리. `KRW` 는 1.0000(호출 없음).
- **null**: 제공처 오류·타임아웃·응답 파싱 실패·응답에 KRW 또는 요청 통화 누락. 예외를 던지지 않는다(폴백을 계약이 소유).
- 요청마다 제공처 1회 호출. 상태 없음.
