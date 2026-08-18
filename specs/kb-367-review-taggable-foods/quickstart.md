# Quickstart: 스캔 음식 목록 조회 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 회원 토큰 $TOKEN 확보, 음식 A·B 를 스캔해 이력 생성(A→B→A 순)
```

## 1. 기본 목록 — 중복 제거·최신 스캔순

```bash
curl -s "localhost:8080/api/foods/scanned?lang=en" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"
# → items = [A, B] (A 가 마지막 스캔이라 맨 앞, 중복 없음)
```

## 2. 비회원 401

```bash
curl -s "localhost:8080/api/foods/scanned?lang=en" -H "X-API-Version: 1.0"
# → 401
```

## 3. keyword 필터

```bash
curl -s "localhost:8080/api/foods/scanned?lang=en&keyword=kimchi" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"
# → 스캔 음식 중 이름 매칭만. 스캔 안 한 음식은 키워드가 맞아도 안 나옴
```

## 4. 커서 페이징 (스캔 음식 21개 이상)

```bash
# 첫 페이지 → nextCursor 확인 후
curl -s "localhost:8080/api/foods/scanned?lang=en&cursor=<nextCursor>" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0"
# → 이어지는 20건, 마지막 페이지는 hasNext=false·nextCursor=null
```

## 5. 경계

- 스캔 이력 없는 회원 → `items: []` 정상 응답.
- 스캔한 음식을 관리자에서 소프트 삭제 → 목록에서 사라짐.
- `lang` 누락 → 400. 비정상 커서(`cursor=abc`) → 400.
