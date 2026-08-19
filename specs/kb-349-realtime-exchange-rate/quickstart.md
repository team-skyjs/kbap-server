# Quickstart: KB-349 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.infra.exchange.*"     # 어댑터: base=EUR 요청·KRW/X 4자리 계산·실패 시 null
./gradlew :api:test --tests "com.kbap.api.scan.*"               # 스캔 2.0: fake 환율·제공처 실패 시 currency=null
./gradlew :api:test --tests "com.kbap.api.member.*"             # 리맵 SQL ↔ enum 동기 + 폐기 코드 거절
./gradlew :common:test --tests "com.kbap.common.domain.*"       # CurrencyCode 30종·CountryCode 재매핑
./gradlew test                                                  # 전체 회귀 (ArchUnit 포함)
```

## 로컬 수동 확인 (키 불필요)

```bash
# 제공처 직접 — 우리가 보내는 요청 그대로
curl -s "https://api.frankfurter.dev/v1/latest?base=EUR" | jq '{date, KRW: .rates.KRW, USD: .rates.USD, JPY: .rates.JPY}'
# krwPerUnit(USD) = KRW / USD  (예: 1632.3 / 1.1576 = 1410.0725)

SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
TOKEN=<accessToken>
curl -s -X POST "http://localhost:8080/api/scans?currency=JPY" \
  -H "X-API-Version: 2.0" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"imagePath":"..."}' | jq '.payload.currency'
# 기대: { "code": "JPY", "krwPerUnit": <당일 KRW/JPY 4자리> }. 매 호출마다 제공처를 부른다(캐시 없음).

# 폐기 통화 → 400
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8080/api/scans?currency=VND" -H "X-API-Version: 2.0" -H "Authorization: Bearer $TOKEN"
```

## 구현 후 확인

- Flyway 적용 후 `SELECT currency, COUNT(*) FROM member GROUP BY currency` 에 폐기 18종이 0건.
- agent-hub `wiki/member-currency.md` 의 "환율 — 고정 스냅샷이다" 절을 "frankfurter 요청마다 조회·30종·캐시 없음(검토 경위 포함)" 으로 갱신(KB-349 머지 시).
- 프론트 공유: contracts/scan-v2-currency.md 의 "클라이언트 공유 요점" 3건.
