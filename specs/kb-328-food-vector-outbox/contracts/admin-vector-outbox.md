# Contract: 관리자 벡터 아웃박스 대시보드·재처리 (Thymeleaf)

REST admin API 를 만들지 않는다 — 기존 관리자 화면(`/admin/foods` 대시보드) 확장. 인증은 기존 `AdminPageAuthInterceptor` 그대로.

## 대시보드 (기존 음식 대시보드에 섹션 추가)

- 벡터 아웃박스 상태별 카운트: PENDING / COMPLETE / FAILED
- FAILED 목록(최신순, 상한 20): outboxId · foodId · 음식 표시명 · operation · attempts · last_error · updated_at

## 재처리

- `POST /admin/foods/vector-outboxes/{outboxId}/retry` (form)
- 효과: FAILED → PENDING, attempts = 0, last_error 유지(원인 추적용). FAILED 가 아닌 건은 no-op(리다이렉트만).
- 처리 자체는 다음 `foodVectorSyncJob` 실행이 담당 — 화면은 큐잉만 한다.
- 응답: 대시보드로 redirect (기존 관리자 화면 관례 — GET 앵커 점프 없음).
