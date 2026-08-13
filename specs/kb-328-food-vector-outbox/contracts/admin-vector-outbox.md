# Contract: 관리자 벡터 아웃박스 대시보드·재처리 (Thymeleaf)

REST admin API 를 만들지 않는다 — 기존 관리자 화면(`/admin/foods` 대시보드) 확장. 인증은 기존 `AdminPageAuthInterceptor` 그대로.

## 대시보드 (기존 음식 대시보드에 섹션 추가)

- 벡터 아웃박스 상태별 카운트: PENDING / COMPLETE / FAILED — 템플릿 DOM id 계약으로 고정: `vector-outbox-count-PENDING`·`vector-outbox-count-COMPLETE`·`vector-outbox-count-FAILED` (요소 텍스트 = 건수. 테스트가 이 id 로 단언하므로 리네임은 계약 변경)
- FAILED 목록(최신순, 상한 20): outboxId · foodId · 음식 표시명 · operation · attempts · last_error · updated_at

## 수동 벡터 적재 (2026-08-13 추가 — 구 Flyway 백필 대체)

- `POST /admin/foods/vector-outboxes/enqueue` (form)
- 효과: **READY·ACTIVE 음식만** 대상으로 UPSERT/PENDING 아웃박스 생성. 같은 (foodId, UPSERT) PENDING 존재 시 건너뜀(중복 억제). **1회 지시당 상한 500건**(재수집 `RECOLLECT_MAX` 와 동형) — 대상이 더 많으면 반복 지시.
- 응답: 대시보드로 redirect. 처리 자체는 다음 `foodVectorSyncJob` 실행이 담당.

## 재처리

- `POST /admin/foods/vector-outboxes/{outboxId}/retry` (form)
- 효과: FAILED → PENDING, attempts = 0, last_error 유지(원인 추적용). FAILED 가 아닌 건은 no-op(리다이렉트만).
- 처리 자체는 다음 `foodVectorSyncJob` 실행이 담당 — 화면은 큐잉만 한다.
- 응답: 대시보드로 redirect (기존 관리자 화면 관례 — GET 앵커 점프 없음).
