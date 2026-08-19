# Quickstart: 스캔 무료 3회·리뷰 해금 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 신규 회원 토큰 $TOKEN (리뷰 0건·스캔 0회)
```

## 1. 무료 3회 소진 → 4회째 403

```bash
# 성공 스캔 3회 수행 후
curl -s -X POST localhost:8080/api/scans -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0" ... 
# 4회째 → 403 {"code":"SCAN-004","message":"무료 스캔 횟수를 모두 사용했습니다. ..."}
# 반복 시도해도 계속 403 — 로그에 비전 호출·이력 기록 없음
```

## 2. 실패 스캔은 미소모

```bash
# 스캔 2회 소진 상태에서 비메뉴판 사진 스캔 → 400 SCAN-003
# 이후 정상 스캔 → 성공(3회째로 카운트) — 실패가 횟수를 까먹지 않음
```

## 3. 리뷰 작성 → 즉시 해금

```bash
curl -s -X POST localhost:8080/api/reviews ... -d '{"foodId": 1, "rating": 5}'
# 직후 스캔 → 200 (대기·재로그인 없음), 이후 몇 회든 무제한
```

## 4. v1 경로도 동일

```bash
# 잠긴 회원으로 v1(무버전) 스캔 → 동일 403 SCAN-004
```

## 5. DB 확인

```sql
SELECT scan_count, review_count, scan_unlocked FROM member WHERE id = ...;
-- 리뷰 작성 후 scan_unlocked = 1, 리뷰 삭제해도 1 유지(재잠금은 후속 배치)
```
