# Research: 환율을 고정 스냅샷에서 실시간 조회로 전환 (KB-349)

## R1. 제공처 — frankfurter (ECB 기준환율), 지원 30종 확정

- **Decision**: `https://api.frankfurter.dev/v1` (무료·키 불필요·ECB 일 1회 고시). 2026-08-19 실측 `GET /v1/currencies` 지원 30종:
  `AUD BRL CAD CHF CNY CZK DKK EUR GBP HKD HUF IDR ILS INR ISK JPY KRW MXN MYR NOK NZD PHP PLN RON SEK SGD THB TRY USD ZAR`.
- **현재 enum 46종과 대조**:
  - **유지 28**: AUD BRL CAD CHF CNY CZK DKK EUR GBP HKD HUF IDR ILS INR JPY KRW MXN MYR NOK NZD PHP PLN SEK SGD THB TRY USD ZAR
  - **폐기 18 → USD**: AED BDT BHD BND EGP FJD JOD KHR KWD KZT MNT NPR PKR QAR RUB SAR TWD VND
  - **추가 2**: ISK(아이슬란드 크로나) RON(루마니아 레우) — "제공처 지원 통화 전부" 규칙(spec FR-002)이라 선별하지 않는다. `CountryCode.IS→ISK`, `RO→RON`.
- **Rationale**: Jira/기획의 "30개국 통화"가 정확히 이 목록이다. KB-322 R9 에서 커버리지 부족으로 기각했던 것을, 이번엔 시세 신선도를 얻는 대가로 USD 대체를 받아들이며 되살린다(spec 맥락).
- **Alternatives**: 유료 API(openexchangerates 등, 170종) — 키·비용·외부 의존이 늘고 기획이 "30개국 한정·USD 대체"로 이미 결정. 기각. 국내 은행 고시 스크래핑 — 비공식·깨지기 쉬움. 기각.

## R2. 기준 통화·정밀도 — `base=EUR` 로 받아 KRW/X 를 4자리로 계산

- **문제**: `base=KRW` 는 frankfurter 가 반올림해 유효숫자가 준다(실측 `USD: 0.00071` — 2자리). KB-322 R9 가 발견한 함정.
- **Decision**: **`GET /v1/latest?base=EUR`** 하나로 전 통화를 받는다(ECB 원 고시가 EUR 기준이라 반올림 없음 — 실측 `KRW 1632.3, USD 1.1576, JPY 184.87, IDR 20677.34`). `krwPerUnit(X) = rate(KRW) ÷ rate(X)`, **HALF_UP 소수 4자리**. EUR 자체는 `rate(KRW) ÷ 1`, KRW 는 1.0000.
  - 검산(Decimal HALF_UP): USD 1632.3/1.1576 = **1410.0726**, JPY = **8.8294**, IDR = **0.0789**.
- **Rationale**: 요청 1회로 30종 전부, 정밀도 손실 없음, `symbols` 파라미터 불필요(전부 받아 우리 enum 에 있는 것만 채택).
- **Alternatives**: `base=KRW&amount=1000000` 트릭 — 동작하지만 의도가 코드에서 안 읽힘. `base=USD` — 두 번 나눠야 함. 기각.

## R3. seam·어댑터·조립 위치 (ADR-0018 패턴)

- **Decision**:
  - seam `common.port.exchange.ExchangeRateClient` — `fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal?` (1단위당 원화 4자리, **조회 실패·누락이면 null** — 폴백을 포트 계약에 넣어 호출자가 try/catch 를 갖지 않게 한다). 포트가 도메인 vocabulary(`CurrencyCode`)를 받는 방향은 허용(헌법 II·III).
  - 어댑터 `api.infra.exchange.FrankfurterExchangeRateClient` — RestClient(JDK HttpClient, **connect 1s / read 2s** — 사용자 요청 경로에서 매번 부르므로 짧게), Jackson 매퍼 직접 소유(Boot 4 이중 클래스패스 함정 — wiki `boot4-jackson-dual-classpath-pitfall`). `KRW` 는 호출 없이 1.0000. 요청당 `GET /v1/latest?base=EUR` 1회 → `KRW/X` 계산. `RestClientException`·변환 예외·응답에 KRW 또는 X 누락 → warn 로그 후 **null**(Google 어댑터의 `callGoogle` 과 같은 execute-around 로 감싼다).
  - 조립 `api.core.config.ExchangeConfig` — `@ConditionalOnMissingBean(ExchangeRateClient)` 로 어댑터 빈. 테스트는 `@Primary` fake 로 교체(`FakePlaceSearchClient` 와 동일 패턴).
  - 프로퍼티 `kbap.exchange.base-url`(기본 `https://api.frankfurter.dev`) 하나. API 키 없음 — `.env.example` 변경 없음.
- **Rationale**: 소비자가 api 뿐이라 어댑터는 `api.infra`(batch 도 쓰게 되면 `common.infra` 로 승격). Google Places 어댑터와 완전히 같은 골격이라 읽는 사람이 새로 배울 게 없다.

## R4. 캐시 — 두지 않는다 (사용자 확정, 2026-08-19)

- **Decision**: 스캔 요청마다 제공처를 1회 호출한다. 저장·TTL·스케줄러 없음.
- **검토 경위**: ① 초안 JVM `@Volatile` 스냅샷 — 다중 인스턴스 운영이라 호출 N배·인스턴스 간 환율 불일치로 부적합(사용자 지적). ② Redis 공유 스냅샷(단일 키, `fetchedAt` 으로 신선도 판정, 락 없음) — 동작은 하지만 seam·어댑터·서비스·테스트 4벌이 늘어 지금 단계엔 과함 → 사용자가 "구현만 먼저" 로 확정.
- **감수하는 비용**: 스캔당 제공처 왕복 1회(수백 ms). 스캔은 LLM 호출로 수 초 걸리는 경로라 체감 영향은 작다. 제공처 장애 동안 전 스캔이 `currency=null`. 제공처(무료)에 스캔 QPS 만큼 부하 — 현재 트래픽에선 무시.
- **업그레이드 경로**(호출량이 문제 될 때): Redis 단일 키 공유 스냅샷 + 하루 1회 갱신(②안 그대로). 포트 시그니처 `getKrwPerUnitOrNull(code)` 는 그대로 두고 구현만 "캐시 먼저 보고 없으면 호출" 로 바꾸면 컨트롤러·응답은 무변경.

## R5. 폴백 규칙과 응답 조립

- **Decision**: `ScanV2Controller` 가 `exchangeRateClient.getKrwPerUnitOrNull(requestedCurrency)` 를 받아 `ScanV2Response.from(result, currency, krwPerUnit?)` 에 넘긴다(스캔 서비스 호출 뒤, 트랜잭션 밖). `krwPerUnit == null` 이면 `currency = null`(기존 계약 — "있으면 둘 다, 없으면 통째로 null"). 스캔 본문은 영향 없음.
- **Rationale**: spec FR-006. 응답 계약(`currency { code, krwPerUnit } | null`) 불변. `CurrencyResponse` 스키마 설명의 "참고용 고정 스냅샷" 문구는 "제공처 최근 고시(일 1회 갱신)" 로 고친다.

## R6. `CurrencyCode` 축소·`CountryCode` 재매핑·기존 회원 백필

- **Decision**:
  - `CurrencyCode`: `krwPerUnit` 필드 삭제, 30종으로 교체(label 유지 — 개발자 가독성용). `from()` 은 그대로 → 폐기 코드는 프로필 수정에서 `INVALID_CURRENCY_CODE`(spec US2-3).
  - `CountryCode`: 폐기 통화를 가리키던 18개국 → `USD`, `IS→ISK`, `RO→RON`. 컴파일러가 197개 전수를 강제하므로 누락 불가.
  - 기존 회원 이관: **Flyway 미사용 — 운영자 수동 UPDATE**(2026-08-19 사용자 확정. dev 실측 대상이 BDT 1건). 실행할 SQL: `UPDATE member SET currency='USD' WHERE currency IN ('AED','BDT','BHD','BND','EGP','FJD','JOD','KHR','KWD','KZT','MNT','NPR','PKR','QAR','RUB','SAR','TWD','VND');` — 환경별(dev·staging·prod) 배포 전후 각 1회. IS/RO 기존 회원은 건드리지 않는다(사용자 값을 말없이 바꾸지 않는 KB-322 원칙).
  - `CurrencyBackfillSyncTest`(KB-322 SQL ↔ enum 전수 대조) 는 축소 후 **참으로 유지될 수 없다** → 삭제하고 `CurrencyRemapSyncTest` 로 대체: "KB-322 백필 SQL 통화 집합 − 현재 enum == 폐기 18종 고정 목록"(수동 UPDATE 의 IN 목록과 같은 집합) + "역방향 차집합 == ISK·RON". enum 이 바뀌면 테스트가 먼저 깨져 수동 SQL 목록 갱신을 강제한다.
- **Rationale**: 하드코딩 환율 제거(FR-001)·지원 목록 고정(FR-002)·기존 회원 무오류(FR-004).

## R7. 테스트 전략

- `FrankfurterExchangeRateClientTest`(MockRestServiceServer): 요청 경로·`base=EUR`, EUR 응답 → KRW/X 4자리(USD 1410.0726·JPY 8.8294·IDR 0.0789·EUR 1632.3000), KRW 는 호출 없이 1.0000, 응답에 요청 통화 누락 → null, HTTP 오류·파싱 실패 → null(예외 전파 안 함).
- `ScanControllerTest`: fake `ExchangeRateClient`(@Primary, 통화→값 맵 + null 스위치) 로 기존 8.8906/1416.0000 기대를 fake 값으로 교체 + "제공처 실패 → 스캔 성공·currency=null" 케이스 추가.
- `CurrencyCodeTest`: krwPerUnit 단정 삭제 → 30종·KRW 포함·폐기 코드 `from()==null`. `CountryCodeTest`: VN→USD·TW→USD·IS→ISK·RO→RON. `CurrencyRateSnapshotTest` 삭제(스냅샷 소멸 — 4자리 정밀도 회귀는 어댑터 테스트의 IDR 0.0789 가 계승).
- `CurrencyRemapSyncTest`: R6 대로. `MemberProfileTest`·회원 프로필 컨트롤러 테스트: 폐기 코드 거절 유지 확인.
