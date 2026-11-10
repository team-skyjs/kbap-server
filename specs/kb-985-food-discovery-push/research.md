# Research: KB-985

- 사전 조사: `_workspace/kb985-food-discovery-push/01_popular_food_research.md`(오케스트레이터).
- `overallRisk == SAFE` 는 1~9% 포함 재료를 SAFE 로 볼 수 있어 false-safe → 사용 금지. `overlappedIngredients(codeNames).isEmpty()` 의미로 판정.
- `Avoidance.codeNames` 는 `impliedCodes` 확장 포함. 회원 프로필에서 읽어야 하며 직접 SQL 금지.
- ShedLock 은 batch 모듈에 없음 → `BatchJobLauncher` AlreadyRunning + 리더 NOT EXISTS 로 멱등.
- 실패 발송은 `PushDispatchService.record` 가 notification 을 soft delete → `@SQLRestriction` 으로 이력 조회에서 자동 제외.
