# Quickstart: 관리자 대시보드 확장 (kb-264)

## 테스트 (TDD 사이클)

```bash
# 집계 서비스 + 페이지 테스트 (Testcontainers MySQL — Docker 필요)
./gradlew :api:test --tests "com.kbap.api.admin.AdminDashboardMetricsServiceTest"
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodPageControllerTest"

# 전체 검증 (ArchUnit 포함)
./gradlew build
```

## 로컬에서 화면 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

1. `http://localhost:8080/admin/login` 접속 → 관리자 계정 로그인
2. `/admin/foods`(적재 현황) 진입
3. 확인 포인트:
   - 기존 적재 현황 카드 5개 + READY 비율 그대로
   - 총 가입자 수 카드(ACTIVE 회원 수)
   - 최근 7일 바 차트 3개(스캔 횟수 · 신규 등록 음식 · LLM 호출 비용 USD) — 데이터 없는 날은 0, 요일 라벨 표시

## 데이터가 비어 보일 때

- 스캔/비용 그래프는 최근 7일 데이터만 집계한다 — 오래된 로컬 데이터는 0 으로 보이는 게 정상.
- 로컬에서 값을 만들려면: 스캔 API 호출(scan_history), 음식 등록(`/admin/foods/seed`), 배치 LLM 호출(llm_call_cost) 후 새로고침.
