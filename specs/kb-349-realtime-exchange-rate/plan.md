# Implementation Plan: 환율을 고정 스냅샷에서 실시간 조회로 전환

**Branch**: `kb-349-realtime-exchange-rate` | **Date**: 2026-08-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-349-realtime-exchange-rate/spec.md`

## Summary

`CurrencyCode.krwPerUnit` 하드코딩 스냅샷을 없애고, 2.0 스캔 응답의 환율을 **스캔 요청마다 frankfurter(ECB) 를 1회 호출해** 채운다(캐시 없음 — 사용자 확정). 취급 통화는 제공처 지원 30종으로 고정(28 유지·18 폐기→USD·ISK/RON 추가), 폐기 통화를 가진 기존 회원은 Flyway UPDATE 로 USD 이관. 제공처 실패 시 `currency=null` 로 스캔은 성공. 응답 계약 불변. Refs KB-349.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1
**Primary Dependencies**: RestClient + `HttpServiceProxyFactory`(Google Places 어댑터와 동일), Jackson 3(어댑터가 매퍼 직접 소유), Flyway
**Storage**: MySQL — `member.currency` 값 UPDATE(스키마 변경 없음). 환율은 저장하지 않는다.
**Testing**: Kotest BehaviorSpec — MockRestServiceServer(어댑터)·MockMvc+Testcontainers(스캔 통합, fake seam @Primary)
**Target Platform**: `:api` 만(소비자 = 2.0 스캔). batch 무관.
**Project Type**: web-service (모듈러 모놀리스 `:common`·`:api`)
**Performance Goals**: 스캔 응답 지연 증가 = 제공처 왕복 1회(정상 수백 ms, 무응답 시 connect 1s + read 2s 한도 후 null)
**Constraints**: 응답 계약 불변(`currency {code, krwPerUnit} | null`), 캐시 없음(요청당 1회 호출), 제공처 장애가 스캔을 막지 않음, 외부 호출은 스캔 트랜잭션 밖
**Scale/Scope**: 통화 30종, 응답 필드 1개, 신규 파일 5개(포트·어댑터·config·마이그레이션·fake), 수정 ~8개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|---|---|---|
| I. Test-First | PASS | 어댑터·스캔 통합·enum/매핑·리맵 SQL 테스트를 먼저 Red 로 두고 구현(research R7). |
| II. Bounded Contexts | PASS | `CurrencyCode` 는 공유 vocabulary(`common.domain` 루트) 유지. 신규 seam 은 `common.port.exchange`, 소비는 `api.scan` 컨트롤러. 도메인 간 새 의존 없음(`ModuleBoundaryTest` 맵 무변경). |
| III. Layered Dependency | PASS | 포트(`common.port.exchange`) → 어댑터(`api.infra.exchange`) → 조립(`api.core.config.ExchangeConfig`). 어댑터 직접 참조는 config 뿐(ArchUnit 강제). 포트가 `CurrencyCode` 를 받는 방향은 허용, 역방향 없음. |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리 변경 없음. Flyway(owner=api) 로 값 UPDATE 1건. 환율은 영속하지 않는다. |
| V. Language Policy | N/A | 언어 무관. |
| 추가 제약 — 외부 호출을 트랜잭션 안에서 잡지 않음 | PASS | 제공처 호출은 컨트롤러 계층(`ScanV2Controller`)에서 스캔 서비스 반환 뒤 — 트랜잭션 밖. |

**Post-Phase-1 재검토**: data-model·contracts 작성 후에도 위 판정 불변 — 신규 엔티티 없음, 응답 스키마 불변, 새 도메인 방향 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-349-realtime-exchange-rate/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── scan-v2-currency.md
└── tasks.md            # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── domain/CurrencyCode.kt                       # 수정: krwPerUnit 삭제, 30종
├── domain/member/model/CountryCode.kt           # 수정: 18개국→USD, IS→ISK, RO→RON
└── port/exchange/ExchangeRateClient.kt          # 신규 seam: getKrwPerUnitOrNull(currency)

api/src/main/kotlin/com/kbap/api/
├── infra/exchange/FrankfurterExchangeRateClient.kt   # 신규 어댑터 (+ HTTP interface 같은 파일 또는 FrankfurterApi.kt)
├── core/config/ExchangeConfig.kt                # 신규 조립
├── scan/ScanV2Controller.kt                     # 수정: 포트 주입, krwPerUnit 전달
└── scan/ScanV2Response.kt                       # 수정: from(result, currency, krwPerUnit?), 스키마 설명

api/src/main/resources/
├── application.yml                              # 수정: kbap.exchange.base-url
└── db/migration/V<ts>__member_currency_remap.sql # 신규: 폐기 18종 → USD

common/src/test/kotlin/com/kbap/common/domain/
├── CurrencyCodeTest.kt                          # 수정
├── CurrencyRateSnapshotTest.kt                  # 삭제
└── member/model/CountryCodeTest.kt              # 수정

api/src/test/kotlin/com/kbap/api/
├── scan/FakeExchangeRateClient.kt               # 신규 (@Primary test config, FakeMenuBoardVisionExtractor 옆)
├── infra/exchange/FrankfurterExchangeRateClientTest.kt   # 신규
├── member/CurrencyBackfillSyncTest.kt           # 삭제 → CurrencyRemapSyncTest.kt 신규
└── scan/ScanControllerTest.kt                   # 수정
```

**Structure Decision**: seam 은 `:common` port, 어댑터·조립은 `:api`(소비자가 스캔뿐 — ADR-0016 승격 기준 미충족). 중간 서비스 없음 — 컨트롤러가 포트를 직접 호출한다(폴백은 포트 계약 "실패면 null" 이 소유). 캐시를 붙이게 되면 포트 구현만 바꾸거나 포트 앞에 서비스를 한 장 끼운다.

## Complexity Tracking

위반 없음 — 표 생략.
