# Phase 1 Data Model: 버전 파일명 문법 & 설정 키

이 작업은 DB 스키마/엔티티 변경이 없다. "데이터 모델"은 **마이그레이션 파일명 문법**과 **Flyway 설정 키**의 형식적 정의다.

## 1. 마이그레이션 버전 파일명 문법

```
<filename> ::= "V" <version> "__" <description> ".sql"

<version>  ::= <legacy-int> | <dotted-timestamp>

<legacy-int>       ::= <digits>                         # 기존 이력 전용, 신규 금지
<dotted-timestamp> ::= <yyyy> "." <MM> "." <dd> "." <HH> "." <mm> "." <ss>

<yyyy> ::= 4 digit year         (예: 2026)
<MM>   ::= 2 digit month  01–12 (zero-pad)
<dd>   ::= 2 digit day    01–31 (zero-pad)
<HH>   ::= 2 digit hour   00–23 (zero-pad, 24h)
<mm>   ::= 2 digit minute 00–59 (zero-pad)
<ss>   ::= 2 digit second 00–59 (zero-pad)

<description> ::= 소문자 스네이크 슬러그 (예: add_review_table)
```

### 규칙
- **신규 마이그레이션은 `<dotted-timestamp>` 만 사용**한다. 값은 파일 **생성 시점의 로컬 현재 시각**.
- 각 시각 파트는 **두 자리 zero-pad**(월 `07`, 일 `05` — `7`·`5` 금지). 가독성·일관성 목적(Flyway 정렬 자체는 값으로 하므로 zero-pad 없이도 정렬은 되지만 컨벤션으로 고정).
- `<legacy-int>` 는 **기존에 이미 적용된 `V1`~`V10` 전용**. 신규 생성·기존 리네임 금지.

### 정렬(Flyway)
- Flyway 는 버전을 숫자 파트열로 **수치 정렬**한다.
- 정수 `10` < 점 timestamp `2026.07.05.14.30.12` → 기존 정수 이력이 항상 신규 timestamp 앞에 온다.
- fresh DB: 전체를 버전 오름차순 적용. 기존 DB: 미적용분만 적용하되, 이미 적용된 최신보다 과거 버전은 **out-of-order** 로 적용.

### 상태 전이 (한 마이그레이션의 적용 관점)
```
[작성됨: 파일 생성] 
    → [Pending: DB 미적용]
    → 부팅 시 적용
        ├─ 버전이 현재 최신보다 큼 → [Success (정상 순서)]
        └─ 버전이 현재 최신보다 작음(뒤늦은 머지) → [Success (Out of Order)]   # out-of-order=true 필요
```

## 2. Flyway 설정 키

| 키 | 값 | 위치 | 의미 |
|----|----|------|------|
| `spring.flyway.out-of-order` | `true` | `app/api/src/main/resources/application.yml` (베이스) | 이미 적용된 최신보다 과거인 버전의 뒤늦은 적용을 허용(validate 실패 대신 적용) |

- 기본값은 `false`(현재 미설정 상태 = 기본값). 이 작업이 `true` 로 명시.
- 전 실행 프로필(local/dev/staging/prod) 상속. 테스트(H2·flyway off)는 무영향.

## 3. 불변식 (검증 대상)

- **INV-1**: 기존 `V1`~`V10` 파일의 내용·파일명이 변경되지 않는다(checksum 보존).
- **INV-2**: 신규 마이그레이션 버전은 `<dotted-timestamp>` 문법을 만족한다.
- **INV-3**: `out-of-order=true` 가 전 프로필에서 유효하다(과거 timestamp 가 부팅을 막지 않는다).
- **INV-4**: 각 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않는다(순서-독립). — 리뷰·문서 원칙으로 강제(자동 검증 불가).
