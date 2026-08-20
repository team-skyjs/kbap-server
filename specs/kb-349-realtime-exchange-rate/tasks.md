# Tasks: 환율을 고정 스냅샷에서 실시간 조회로 전환 (KB-349)

**Input**: `specs/kb-349-realtime-exchange-rate/` — spec.md·plan.md·research.md·data-model.md·contracts/scan-v2-currency.md·quickstart.md

**Tests**: 헌법 원칙 I(Test-First) — 각 스토리에서 테스트를 먼저 Red 로 확인 후 구현한다.

**Organization**: US1(실시간 환율 조회) → US2(통화 30종 축소·USD 이관) → US3(장애 폴백 검증). US1 은 기존 `krwPerUnit` 필드를 아직 지우지 않은 채 새 공급원을 연결하고, US2 가 하드코딩을 제거한다 — 컴파일이 깨진 중간 상태를 만들지 않기 위한 순서다.

## Phase 1: Setup

- [x] T001 `api/src/main/resources/application.yml` 의 `kbap:` 섹션에 `exchange.base-url: https://api.frankfurter.dev` 추가 (google 섹션 아래, 주석은 yml 규약상 허용 — "스캔 2.0 환율. 키 불필요, 실패 시 currency=null")

## Phase 2: Foundational — seam·어댑터 (스토리 공통 전제)

- [x] T002 seam 신설 `common/src/main/kotlin/com/kbap/common/port/exchange/ExchangeRateClient.kt` — `fun getKrwPerUnitOrNull(currency: CurrencyCode): BigDecimal?` 인터페이스 하나 (Spring-free, research R3)
- [x] T003 [Red] 어댑터 테스트 신설 `api/src/test/kotlin/com/kbap/api/infra/exchange/FrankfurterExchangeRateClientTest.kt` (MockRestServiceServer, `GooglePlaceSearchClientTest` 골격) — `GET /v1/latest?base=EUR` 경로 검증, EUR 픽스처(`KRW 1632.3, USD 1.1576, JPY 184.87, IDR 20677.34`) → USD `1410.0725`·JPY `8.8295`·IDR `0.0789`·EUR `1632.3000`(HALF_UP 4자리), KRW 는 서버 호출 없이 `1.0000`, 응답에 요청 통화 누락 → null, 응답에 KRW 누락 → null, HTTP 5xx → null, 본문 파싱 불가 → null. 실행해 컴파일 실패(Red) 확인
- [x] T004 어댑터 구현 `api/src/main/kotlin/com/kbap/api/infra/exchange/FrankfurterExchangeRateClient.kt` — RestClient(JDK HttpClient connect 1s / read 2s)·Jackson 매퍼 직접 소유·`companion object { BASE_URL; create(baseUrl); internal create(baseUrl, builder) }`, 실패 execute-around(warn 로그 + null). T003 Green 확인
- [x] T005 조립 신설 `api/src/main/kotlin/com/kbap/api/core/config/ExchangeConfig.kt` — `@ConditionalOnMissingBean(ExchangeRateClient::class)` + `@Value("\${kbap.exchange.base-url}")` 로 어댑터 빈 (PlaceConfig 골격)
- [x] T006 테스트 fake 신설 `api/src/test/kotlin/com/kbap/api/scan/FakeExchangeRateClient.kt` — 통화→BigDecimal 가변 맵 + `failAll` 스위치, `@TestConfiguration`+`@Primary` 등록 config 동봉 (`FakePlaceSearchClient` 골격)

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.infra.exchange.*"` 그린. 기존 코드 무변경.

## Phase 3: US1 — 스캔 응답에 최신 환율 (P1) 🎯 MVP

**Goal**: 2.0 스캔 응답의 `currency.krwPerUnit` 이 enum 상수가 아니라 seam 조회값이 된다.

**Independent Test**: fake 가 JPY→9.9999 를 주면 스캔 응답에 9.9999 가 실린다(enum 의 8.8906 이 아님).

- [x] T007 [US1] [Red] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 수정 — `FakeExchangeRateClient` 주입(JPY 9.9999·USD 1400.5 등 enum 과 다른 값), 기존 `8.8906`/`1416.0000` 기대를 fake 값으로 교체, "통화 미설정 회원 + currency 파라미터" 기대 유지. 실행해 Red 확인
- [x] T008 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt` 수정 — `from(result, currency, krwPerUnit: BigDecimal?)` 로 시그니처 변경, `krwPerUnit == null` 이면 `currency = null`, `CurrencyResponse` 스키마 설명을 "제공처 최근 고시(일 1회 갱신 수준)·실패 시 currency 통째로 null" 로 갱신
- [x] T009 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanV2Controller.kt` 수정 — `ExchangeRateClient` 주입, 스캔 서비스 반환 뒤 `getKrwPerUnitOrNull(requestedCurrency)` 호출해 `from` 에 전달. T007 Green 확인
- [x] T010 [US1] `MenuScanScenarioTest` 등 2.0 스캔을 지나는 다른 통합 테스트가 fake 부재로 실제 HTTP 를 치지 않는지 확인·필요 시 fake config 추가 — `./gradlew :api:test --tests "com.kbap.api.scan.*" --tests "com.kbap.api.scenario.*"` 그린

**Checkpoint**: US1 단독 배포 가능 — 환율 출처만 바뀌고 enum·회원 데이터 불변.

## Phase 4: US2 — 통화 30종 축소·미지원 국가 USD (P1)

**Goal**: 하드코딩 환율 제거, 취급 통화 = frankfurter 30종, 폐기 통화 회원 USD 이관.

**Independent Test**: `CurrencyCode.from("VND") == null`, `CountryCode.VN.currency == USD`, 마이그레이션 후 member.currency 에 폐기 코드 0건.

- [x] T011 [P] [US2] [Red] `common/src/test/kotlin/com/kbap/common/domain/CurrencyCodeTest.kt` 수정 — krwPerUnit 단정 삭제, 30종 전수(`AUD…ZAR`+`ISK`·`RON`)·폐기 18종 `from()==null`·KRW 포함 단정으로 교체
- [x] T012 [P] [US2] [Red] `common/src/test/kotlin/com/kbap/common/domain/member/model/CountryCodeTest.kt` 수정 — `VN→USD`·`TW→USD`·`SA→USD`·`AE→USD`·`IS→ISK`·`RO→RON` + "전 국가의 currency 가 CurrencyCode 30종 안" 전수 단정
- [x] T013 [US2] `common/src/main/kotlin/com/kbap/common/domain/CurrencyCode.kt` 재작성 — `krwPerUnit` 필드 삭제, 30종(label 유지·ISK "아이슬란드 크로나"·RON "루마니아 레우" 추가)
- [x] T014 [US2] `common/src/main/kotlin/com/kbap/common/domain/member/model/CountryCode.kt` 수정 — 폐기 통화 참조 18개국 → `CurrencyCode.USD`, `IS→ISK`, `RO→RON` (컴파일러가 전수 강제). T011·T012 Green 확인
- [x] T015 [US2] `common/src/test/kotlin/com/kbap/common/domain/CurrencyRateSnapshotTest.kt` 삭제 (research R7 — 정밀도 회귀는 T003 의 IDR 0.0789 가 계승)
- [x] T016 [US2] ~~Flyway 신설~~ **취소(2026-08-19 사용자 확정)** — 운영자 수동 UPDATE 로 대체. 원 계획: `api/src/main/resources/db/migration/V<생성시각 timestamp>__member_currency_remap.sql` — `UPDATE member SET currency='USD' WHERE currency IN ('AED','BDT','BHD','BND','EGP','FJD','JOD','KHR','KWD','KZT','MNT','NPR','PKR','QAR','RUB','SAR','TWD','VND');` + 헤더 주석(KB-349 경위·IS/RO 기존 회원 불변 사유)
- [x] T017 [US2] `api/src/test/kotlin/com/kbap/api/member/CurrencyBackfillSyncTest.kt` 삭제 → 같은 자리에 `CurrencyRemapSyncTest.kt` 신설 — (KB-322 백필 SQL 의 통화 집합 − 현재 CurrencyCode 집합) == T016 UPDATE 의 IN 목록, IN 목록 ∩ 현재 enum == 공집합, 두 SQL 파일 모두 비어 있지 않음(경로 오탈 방어). `given` 문구에 파일 버전 박지 않기
- [x] T018 [US2] 폐기 통화 잔존 참조 정리 — `grep -rn "VND\|TWD\|SAR\b" --include="*.kt" api common` 으로 테스트·픽스처의 폐기 코드 사용처(예: `ScanControllerTest` 의 지원 통화 파라미터, `MemberProfileTest`·프로필 컨트롤러 테스트의 통화 값)를 30종 내 코드로 교체하고, 폐기 코드 400 거절(`INVALID_CURRENCY_CODE`) 케이스를 프로필 수정 테스트에 유지·보강

**Checkpoint**: `./gradlew :common:test :api:test` 그린 — 하드코딩 환율 소멸, US2 인수 시나리오 충족.

## Phase 5: US3 — 제공처 장애에도 스캔 성공 (P2)

**Goal**: 제공처 실패가 스캔을 막지 않음을 스캔 계층에서 고정.

**Independent Test**: fake `failAll` 상태에서 2.0 스캔 → 200 + `results` 정상 + `currency == null`.

- [x] T019 [US3] [Red→Green] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 케이스 추가 — `when("환율 제공처가 실패하면")` fake `failAll` → 스캔 200·`$.payload.currency` null·`$.payload.results` 비어 있지 않음 (구현은 T008-T009 로 이미 충족 — Green 즉시 확인, 아니면 수정)

**Checkpoint**: US3 인수 시나리오 충족(타임아웃 한도는 T004 의 1s/2s 설정이 담당).

## Phase 6: Polish & Cross-Cutting

- [x] T020 [P] `specs/kb-349-realtime-exchange-rate/contracts/scan-v2-currency.md` "클라이언트 공유 요점" 최종 검토·swagger 그룹 문서(2.0)에서 `currency` 스키마 설명 반영 확인
- [x] T021 전체 회귀 `./gradlew test` (ArchUnit 포함 — `common.port.exchange` Spring-free·어댑터 직접 참조 config 한정 검증 통과 확인)
- [x] T022 agent-hub 갱신 — `../kbap-agenthub/wiki/member-currency.md` "환율 — 고정 스냅샷이다" 절을 "frankfurter 요청마다 조회·30종·캐시 없음(검토 경위: JVM→Redis→없음)" 으로 교체, `sprint-8-backlog.md` KB-349 항목에 결과 한 줄, 허브 커밋·푸시

## Dependencies & Execution Order

```
Phase 1 (T001)
  └─ Phase 2 (T002 → T003 → T004 → T005, T006)
       └─ Phase 3 US1 (T007 → T008 → T009 → T010)
            └─ Phase 4 US2 (T011·T012 [P] → T013 → T014 → T015·T016·T017 → T018)
                 └─ Phase 5 US3 (T019)
                      └─ Phase 6 (T020 [P]·T021 → T022)
```

- US2 가 US1 뒤인 이유: `krwPerUnit` 삭제(T013)는 `ScanV2Response` 가 enum 을 읽지 않게 된 뒤(T008)에만 컴파일이 유지된다.
- [P] 후보: T011·T012(서로 다른 테스트 파일), T020(문서).

## Implementation Strategy

- **MVP = Phase 1~3**: 환율 출처만 교체된 상태로도 배포 가능(enum·데이터 불변). 이후 Phase 4 가 계약(30종)을 조인다.
- 커밋 단위 제안: ① Phase 1–3 (`feat(scan): 스캔 2.0 환율을 frankfurter 실시간 조회로 전환`) ② Phase 4–5 (`feat(member): 통화 30종 축소·폐기 통화 USD 이관`) ③ Phase 6 문서.
