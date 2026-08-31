# Quickstart: 스캔 2.0 similarFood 제거 수동 검증

## 준비

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 회원 토큰 $TOKEN, presign 업로드 완료된 메뉴판 사진 $IMAGE_PATH (미등록 메뉴 포함)
```

## 1. 스캔 2.0 — similarFood 부재 확인

```bash
curl -s -X POST localhost:8080/api/scans \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 2.0" -H "Content-Type: application/json" \
  -d '{"imagePath": "'$IMAGE_PATH'", "lang": "en", "currency": "USD"}'
# → 모든 항목에 similarFood 키 자체가 없음
# → 비매칭 항목: matched=false, riskLevel=UNKNOWN, 비전 정제 한국어명·가격 그대로, avoidances=[]
# → 매칭 항목: 위험도·avoidances·이름 규칙 종전과 동일
```

## 2. v2 빈 추출 400 유지 (KB-330)

```bash
# 메뉴판이 아닌 사진으로 스캔 → 400 MENU_BOARD_NOT_DETECTED (similarFood 제거에 휩쓸리지 않았는지)
```

## 3. 스캔 1.0 무변경

```bash
curl -s -X POST localhost:8080/api/scans -H "X-API-Version: 1.0" ... -d '{"imagePath": ..., "items": [...], "lang": "en"}'
# → 종전과 동일 (원래 similarFood 없음)
```

## 4. 검색 미호출 확인

- 비매칭 메뉴 스캔 시 로그에 임베딩·벡터 검색 호출 흔적이 없어야 한다(응답 지연 감소 체감).
