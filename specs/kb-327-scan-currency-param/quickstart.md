# Quickstart: 스캔 2.0 통화 환산 기준을 currency 요청 파라미터로 전환

**Date**: 2026-08-11 | **Plan**: [plan.md](plan.md)

## 자동 검증 (권장 경로)

```bash
./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"   # 기능 시나리오
./gradlew build                                                       # 전 모듈 + ArchUnit
```

통합 테스트는 MySQL Testcontainers 로 돌므로 Docker 만 있으면 된다. LLM seam 은 테스트 픽스처가 대체한다.

## 수동 검증 시나리오 (local 프로필 + 유효 JWT 전제)

공통: `POST /api/scans`, 헤더 `X-API-Version: 2.0` · `Authorization: Bearer <token>`, query `lang=en`, body `{"imagePath": "<업로드된 메뉴판 키>"}`

| # | 전제 (회원 프로필 통화) | 요청 query | 기대 결과 |
|---|------------------------|-----------|-----------|
| 1 | USD | `currency=JPY` | 200 · `payload.currency == { "code": "JPY", "krwPerUnit": 8.8906 }` — 프로필 무시 |
| 2 | 미설정 | `currency=USD` | 200 · `payload.currency.code == "USD"` |
| 3 | USD | (파라미터 없음) | 200 · `payload.currency.code == "USD"` — 기존 동작 |
| 4 | 미설정 | (파라미터 없음) | 200 · `payload.currency == null` — 기존 동작 |
| 5 | 무관 | `currency=XXX` | 400 · `code == "MEMBER-010"` · 스캔 횟수 미증가 |
| 6 | USD | 1.0 호출(`X-API-Version` 생략, `/api/v1/scans`) | 기존 1.0 응답 그대로 — `currency` 필드 없음 |

## Swagger 확인

`/swagger-ui` 의 `X-API-Version: 2.0` 그룹에서 `POST /api/scans` 에 `currency` 선택 파라미터(설명: 파라미터 우선·프로필 fallback·잘못된 값 MEMBER-010)가 노출되는지 확인한다.
