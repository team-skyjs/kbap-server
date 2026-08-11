# Data Model: 스캔 2.0 통화 환산 기준을 currency 요청 파라미터로 전환

**Date**: 2026-08-11 | **Plan**: [plan.md](plan.md)

**신규 엔티티·테이블·마이그레이션 없음.** 기존 모델을 읽기만 하며, 변경은 요청→응답 사이의 통화 결정 규칙뿐이다.

## 재사용 모델

| 모델 | 위치 | 역할 | 변경 |
|------|------|------|------|
| `CurrencyCode` | `common.domain` 루트 (공유 vocabulary) | 지원 통화 46종 + `krwPerUnit` 고정 스냅샷. `from(raw): CurrencyCode?` 정확 일치 lookup | 없음 |
| `MemberProfile.currency` | `common.domain.member.model` | 회원 프로필 통화 — 스캔 경로에서 더 이상 읽지 않음(프로필 기능 전용) | 없음 |
| `ErrorCode.INVALID_CURRENCY_CODE` | `common.core.error` | `MEMBER-010` / 400 — 잘못된 통화 값 실패 코드 | 없음 |
| `ScanResult.currency` | `api.scan` | 스캔 유스케이스 결과의 확정 통화(`CurrencyCode?`) | 없음 (값의 출처만 바뀜) |
| `ScanV2Response.CurrencyResponse` | `api.scan` | 응답 DTO `{ code, krwPerUnit }` | `@Schema` 설명만 갱신 |

## 통화 결정 모델 (이번 변경의 전부)

```text
입력: currency 쿼리 파라미터(raw String, 필수)

[요청 경계 — ScanV2Controller]
파라미터 누락          → 400 COMMON-002 (Spring 필수 파라미터 검증) — 스캔 미실행
CurrencyCode.from(raw) → 성공: requestedCurrency = 그 값
                       → 실패: BusinessException(INVALID_CURRENCY_CODE) — 스캔 미실행

[유스케이스 — ScanService]
확정 통화 = requestedCurrency (CurrencyCode, non-null) — 회원 프로필 통화는 읽지 않는다
```

상태 전이 없음 · 영속 쓰기 없음 — 결정 결과는 응답에만 실린다.
