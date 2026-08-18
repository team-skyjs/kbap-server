# Quickstart: KB-350 검증

## 테스트로 검증 (기본)

```bash
./gradlew :api:test --tests "com.kbap.api.infra.place.*"   # 구글 어댑터 단위(요청 형식·매핑·오류)
./gradlew :api:test --tests "com.kbap.api.place.*"          # 장소 API 통합
./gradlew :api:test --tests "com.kbap.api.review.*"         # GOOGLE_PLACE 출처 저장·노출
./gradlew test                                               # 전체 회귀
```

## 로컬 수동 확인 (실키 필요)

```bash
export GOOGLE_PLACES_API_KEY=...   # kbap.google.places-api-key 매핑 확인
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

TOKEN=<accessToken>

# 화면 진입: 주변 식당 20건 (거리순)
curl -s "http://localhost:8080/api/places/nearby?latitude=37.4979&longitude=127.0276&lang=en" \
  -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN" | jq '.payload.items | length'

# 키워드 검색: 단일 20건, hasNext 없음
curl -s "http://localhost:8080/api/places/search?query=김치찌개&latitude=37.4979&longitude=127.0276&lang=vi" \
  -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN" | jq '{count: (.payload.items|length), hasNext: .payload.hasNext}'
# 기대: hasNext = null (필드 없음)

# 구 page 파라미터 무시 확인 (400 아님)
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/places/search?query=a&latitude=37.5&longitude=127.0&lang=en&page=3" \
  -H "X-API-Version: 1.0" -H "Authorization: Bearer $TOKEN"

# 키 미설정으로 부팅 → 검색만 502 PLACE-001 (부팅은 정상이어야 함)
```

## 구현 후 확인

- 배포 저장소(SSM 파라미터 등)에 `GOOGLE_PLACES_API_KEY` 추가·`KAKAO_REST_API_KEY` 제거 — 운영 후속.
- 프론트 공유: contracts/place-search.md 의 "클라이언트 공유 요점" 3건.
