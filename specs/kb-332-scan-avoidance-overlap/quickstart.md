# Quickstart: v2 스캔 응답 기피성분 겹침 표시

## 검증 명령

```bash
# 도메인 단위 스펙 (Food.overlappedCodes)
./gradlew :common:test --tests "com.kbap.common.domain.food.model.*"

# 스캔 통합 스펙 (v2 응답 avoidances — Testcontainers MySQL 필요, Docker 실행 중이어야 함)
./gradlew :api:test --tests "com.kbap.api.scan.*"

# 전체 (ArchUnit 포함)
./gradlew build
```

## 수동 확인 (선택)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 로그인 → 기피성분 등록된 회원 토큰으로:
curl -X POST http://localhost:8080/api/scans \
  -H "X-API-Version: 2.0" -H "Authorization: Bearer $TOKEN" \
  -F "image=@menu.jpg" ...
# results[].avoidances 에 [{code, overlapped}] 확인, 미매칭 항목은 []
```

## 핵심 확인 포인트

1. 매칭 항목: 회원 기피성분 전체가 표시명(lang 해석)·겹침 여부와 함께 나열되고, 겹친 성분엔 음식 상세와 동일 규칙의 riskLevel(SAFE/CAUTION/DANGER)이 붙는다.
2. 미매칭 항목·degraded: `avoidances: []`.
3. 프로필 없는 회원(게스트): 전 항목 `avoidances: null`, 오류 없음.
4. 프로필 보유·기피 미등록 회원: 전 항목 `[]`, 오류 없음.
5. v1 스캔 응답: 변경 없음.
