# Quickstart: 스캔 응답에 회원 통화 환산 정보 제공

## 검증 (테스트)

```bash
./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"
```

핵심 시나리오 (Kotest BehaviorSpec, `ScanControllerTest`):

1. 통화(예: USD)가 설정된 회원의 2.0 스캔 → `payload.currency.code == "USD"`, `payload.currency.krwPerUnit == 1416.0000`.
2. 통화 미설정 회원의 2.0 스캔 → 스캔 성공 + `payload.currency == null`.
3. 1.0 스캔 응답 → `currency` 필드 없음(기존 계약 불변).

## 수동 확인 (local)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

```bash
curl -X POST http://localhost:8080/api/scans \
  -H "Authorization: Bearer <accessToken>" \
  -H "X-API-Version: 2.0" \
  -H "Content-Type: application/json" \
  -d '{"imagePath": "<업로드된 메뉴판 이미지 경로>", "lang": "en"}'
```

응답 `payload.currency` 확인. 회원 통화는 온보딩 국가로 자동 지정되거나 `PATCH /api/v1/members/me/profile` 로 변경할 수 있다.

## 변경 파일 지도

| 파일 | 변경 |
|------|------|
| `api/.../scan/ScanService.kt` | `getMember` 반환값 재사용 → `ScanResult.currency` 채움 |
| `api/.../scan/ScanResult.kt` | `currency: CurrencyCode?` 필드 추가 |
| `api/.../scan/ScanV2Response.kt` | `currency: CurrencyResponse?` + Swagger 스키마 + `from()` 매핑 |
| `api/src/test/.../scan/ScanControllerTest.kt` | 위 3개 시나리오 추가 |
