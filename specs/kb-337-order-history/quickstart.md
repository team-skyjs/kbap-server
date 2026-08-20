# Quickstart: KB-337 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.order.*"           # 저장(좌표 유무·지오코딩 실패)·리스트(커서·썸네일)·상세(총가격·404)
./gradlew :api:test --tests "com.kbap.api.infra.place.*"     # GoogleGeocoder: latlng·ko, OK/ZERO_RESULTS/DENIED/오류 → null
./gradlew :common:test --tests "com.kbap.common.domain.order.*"   # 엔티티 검증 규칙
./gradlew :api:test --tests "com.kbap.api.architecture.*"    # 도메인 방향 맵(order 추가)
./gradlew test                                                # 전체 회귀
```

## 로컬 수동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun   # GOOGLE_PLACES_API_KEY 필요(지오코딩까지 보려면)
TOKEN=<accessToken>

curl -s -X POST "http://localhost:8080/api/orders" -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"menuName":"순두부찌개","quantity":2,"price":9000,"foodId":1}],"latitude":37.5636,"longitude":126.9834}' | jq .

curl -s "http://localhost:8080/api/orders?size=10" -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN" \
  | jq '{totalCount, first: .payload.items[0]}'
# 기대: roadAddress 에 한국어 도로명. 키 미허용이면 null + 서버 warn 로그(REQUEST_DENIED).
```

## 구현 후 확인 (운영 후속)

- **Google Cloud 콘솔**: ① Geocoding API 사용 설정 ② 기존 키의 API 제한 목록에 **Geocoding API 추가**(현재 Places API (New) 만 허용) — 누락 시 주소가 조용히 전부 null.
- 프론트 공유: contracts/order-api.md "클라이언트 공유 요점" 4건.
- agent-hub: 주문 도메인 문서(좌표 미노출·스냅샷 원칙·썸네일 표시 시점 치환 결정) 추가.
