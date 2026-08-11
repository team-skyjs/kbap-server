# Phase 1 Data Model: 회원 통화 설정

**Feature**: kb-322-member-currency | **Date**: 2026-08-11

## 스키마 변경

### `member` 테이블 — 컬럼 1개 추가

| 컬럼 | 타입 | NULL | 기본 | 설명 |
|------|------|------|------|------|
| `currency` | `varchar(3)` | **YES** | NULL | 회원이 쓰는 통화 코드(ISO 4217 3자 대문자). 온보딩에서 국가 기준으로 채워지고 이후 프로필 수정으로 바뀐다. |

**nullable 인 이유가 둘이다.**
1. **정상 상태** — 온보딩 전 회원은 국가가 없어(`country_code` 도 NULL) 통화도 없다.
2. **배포 안전** — 블루/그린 중 구 리비전이 신 스키마 위에서 돈다. `NOT NULL` + 기본값으로 추가하면 구 리비전의 INSERT 가 컬럼을 모른 채 성공해야 하므로 기본값이 필요하고, 그 기본값이 "잘못된 통화"로 남는다. nullable expand 가 안전하다.

기존 `country_code varchar(2)` 와 나란히 두며, 길이만 3으로 다르다(ISO 4217).

### 마이그레이션 2단계 — 한 파일

```sql
ALTER TABLE `member` ADD COLUMN `currency` varchar(3) NULL;

UPDATE `member`
SET `currency` = CASE `country_code`
    WHEN 'KR' THEN 'KRW'
    ...  -- 197개 전수
END
WHERE `country_code` IS NOT NULL;
```

`country_code IS NULL` 인 회원은 손대지 않는다(FR-011).

## 도메인 타입

### `CurrencyCode` (신규) — `com.kbap.common.domain`

공유 vocabulary. `LanguageCode` 와 같은 자리·같은 성격이다(고정 reference taxonomy 의 식별자 enum, 헌법 원칙 V).

```kotlin
enum class CurrencyCode(val label: String, val krwPerUnit: BigDecimal)
```

- **46종** — 국내 은행 고시 대상 45종 + 원화
- 코드 = enum 이름 = 저장 문자열(예: `KRW`·`JPY`·`USD`·`EUR`)
- `label`(한국어)은 개발자 가독성용 — 런타임 미사용·비권위. 사용자 노출 표시명·기호는 클라이언트 소유
- `krwPerUnit` 은 **non-null** — 46종 전부가 환율을 갖는다
- 파싱은 **정확 일치**(`from(raw): CurrencyCode?`) — trim·대소문자 보정을 하지 않는다

#### 환율 스냅샷 (코드 주석 금지 규약에 따라 여기 기록한다)

| 항목 | 값 |
|------|-----|
| 출처 | 국내 은행 고시 **매매기준율**(송금·현찰 매매가가 아닌 스프레드 없는 중간값) |
| 성격 | **고정 스냅샷** — 실시간 갱신하지 않는다. 시간이 지나면 실제와 벌어지며 참고용 근사로 취급한다 |
| 단위 | **1단위당 원화 금액**. 고시의 `100엔`·`100루피아`·`100동` 은 100으로 나눠 정규화했다 |
| 자릿수 | **소수점 4자리 통일**. 1원 미만 통화(VND 0.0544·IDR 0.0805)를 2자리로 줄이면 VND 가 8% 어긋난다 |
| 타입 | `BigDecimal` 문자열 리터럴 — `Double` 은 값을 정확히 표현하지 못해 금액 오차가 누적된다 |

환산은 `원화 금액 / krwPerUnit` 이다. 통화별 소수점 자릿수·반올림 규칙은 이 기능의 범위 밖이다(KB-323).

### `CountryCode` (수정) — `com.kbap.common.domain.member.model`

```kotlin
enum class CountryCode(val label: String, val currency: CurrencyCode) {
    KR("대한민국", CurrencyCode.KRW),
    JP("일본", CurrencyCode.JPY),
    ...
}
```

**197개 전수 매핑을 컴파일러가 강제**한다(FR-004). 국가→통화는 1:1, 통화→국가는 1:N(유로존 국가가 모두 `EUR` 등).

취급 통화 46종을 쓰는 국가 **80개**는 실제 통화를, 그 밖의 통화를 쓰는 **117개국은 `USD` 로 대체** 지정한다. 통화가 비어 조회·환산이 막히는 상태를 만들지 않기 위한 선택이다.

### `MemberProfile` (수정) — 값 객체

| 필드 | 타입 | 비고 |
|------|------|------|
| `nickname` | `String?` | 기존 |
| `avoidanceSubstanceCodes` | `Set<AvoidedIngredientCodeRef>` | 기존 |
| `spicinessPreference` | `SpicinessPreference` | 기존 |
| `countryCode` | `CountryCode?` | 기존 |
| `profileImageUrl` | `String?` | 기존 |
| **`currency`** | **`CurrencyCode?`** | **신규** |

`updatedWith(currency: String? = null)` 이 추가된다. 기존 규약을 그대로 따른다 — **전달된 필드만 검증 후 교체, `null` 은 기존 값 유지**. 이 규약이 FR-006(미전송 시 유지)을 그대로 준다.

`validatedCurrency(raw) = CurrencyCode.from(raw) ?: throw BusinessException(INVALID_CURRENCY_CODE)`.

**`updatedWith` 가 유일한 검증 경로**라는 기존 불변(파일 주석에 명시)을 통화도 따른다.

## 상태 전이

```
가입 직후        currency = null   (country_code 도 null)
    │
    │ 온보딩 완료 (국가 확정)
    ▼
currency = country.currency        ← 자동 지정, 요청 필드 아님
    │
    ├─ 프로필 수정: currency 전송   → 그 값으로 교체
    ├─ 프로필 수정: currency 미전송 → 유지
    └─ 프로필 수정: country 변경    → 통화 불변 (FR-007)
```

**자동 지정이 일어나는 지점은 온보딩 하나뿐**이다. 그 이후로 국가와 통화는 완전히 독립적이며, "직접 바꿨는지" 같은 추가 상태를 두지 않는다.

## 에러 코드

| 코드 | HTTP | 메시지 | 발생 |
|------|------|--------|------|
| `INVALID_CURRENCY_CODE` = **`MEMBER-010`** | 400 | 지원하지 않는 통화 코드입니다 | 프로필 수정에 지원 목록 밖 값 |

MEMBER 는 001~006·008·009 사용 중이다. **007 은 폐기 번호라 재사용하지 않는다**(CLAUDE.md 규약). 형식·유일성은 기존 `ErrorCodeStatusTest` 가 자동 검증한다.

## 정합 보장

매핑이 **enum(코드)** 과 **백필 마이그레이션(SQL)** 두 곳에 존재한다. `CurrencyBackfillSyncTest` 가 마이그레이션 SQL 을 읽어 `CASE` 분기 집합이 `CountryCode` 전수의 (국가, 통화) 쌍과 일치하는지 검증한다 — `IngredientCatalogSeedSyncTest` 와 같은 방식이다. 컬럼이 nullable 로 추가되는지도 함께 고정한다.

**주의**: 그 테스트는 마이그레이션 **파일명을 리소스 경로로 하드코딩**한다. 파일명·위치를 바꾸면 내용이 빈 문자열로 읽혀 "파일 없음"이 아니라 **데이터 불일치 실패**로 조용히 깨진다.

## 범위 밖

환율·금액 환산·통화별 소수점 자릿수는 이 기능에 없다. `member.currency` 를 읽어 스캔 응답에 환산 금액을 얹는 것은 KB-323 이다.
