# Quickstart: 스캔 무료 3회·리뷰 해금 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 신규 회원 토큰 $TOKEN (리뷰 0건·스캔 0회)
```

## 1. 티켓 발급 → 스캔, 무료 3회 소진 → 4회째 발급 403

```bash
# 매 스캔 시도 전 티켓 발급
TICKET=$(curl -s -X POST localhost:8080/api/scans/tickets -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0" | jq -r .payload.ticket)
curl -s -X POST localhost:8080/api/scans -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0" -H "X-Scan-Ticket: $TICKET" ...
# 성공 스캔 3회 수행 후 4회째 발급 → 403 {"code":"SCAN-004", ...} — 업로드 전에 차단
# 반복 시도해도 계속 403 — 로그에 비전 호출·이력 기록 없음
# 티켓 없이/위조 티켓으로 스캔 강행 → 400 (SCAN-007)
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

## 4. v1 은 제한 밖

```bash
# 잠긴 회원으로 v1(무버전) 스캔 → 정상 200, scan_count 만 누적 (구버전 앱 계약 보존 — 크레딧은 v2 전용)
```

## 5. DB 확인

```sql
SELECT scan_count, review_count, scan_unlocked FROM member WHERE id = ...;
-- 리뷰 작성 후 scan_unlocked = 1, 리뷰 삭제해도 1 유지(재잠금은 후속 배치)
```
