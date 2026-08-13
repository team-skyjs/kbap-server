# Quickstart: KB-328 벡터 아웃박스 동기화 검증

## 자동 테스트

```bash
./gradlew build                                  # 전체 (ArchUnit + Testcontainers 포함)
./gradlew :common:test --tests "com.kbap.common.domain.food.*VectorOutbox*"
./gradlew :api:test --tests "com.kbap.api.admin.*"        # 승인·수정·삭제 훅
./gradlew :batch:test --tests "com.kbap.batch.vector.*"   # 판정 로직 (fake seam)
```

## 로컬 수동 검증 (docker-compose MySQL 기준)

1. api 기동 → Flyway 가 `food_vector_outbox` 테이블 생성(자동 백필 없음 — 적재는 관리자 화면에서 수동 지시):
   ```bash
   SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
   # 확인: SELECT outbox_status, COUNT(*) FROM food_vector_outbox GROUP BY 1;
   ```
2. 관리자 화면(`/admin/foods`)에서 PENDING_REVIEW 음식 승인 → 같은 트랜잭션 커밋 후 UPSERT/PENDING 행 생성 확인. 음식 수정(READY 유지)·삭제도 각각 UPSERT/DELETE 행 확인.
3. 배치 실행 (임베딩·DocumentDB 설정 필요 — dev 자격):
   ```bash
   SPRING_PROFILES_ACTIVE=local \
   KBAP_LLM_EMBEDDING_ENABLED=true \
   KBAP_VECTOR_ENABLED=true KBAP_VECTOR_URI='mongodb://<dev-documentdb>' \
   ./gradlew :batch:bootRun --args='--spring.batch.job.enabled=true --spring.batch.job.name=foodVectorSyncJob'
   ```
4. 결과 확인:
   - MySQL: 아웃박스 전 건 COMPLETE(실패 건은 attempts·last_error).
   - DocumentDB(mongosh): `db.foods.findOne({foodId: <id>})` — v2 필드(embeddingHash·indexedAt 등) 존재.
   - 같은 job 재실행 → 임베딩 호출 없이 즉시 COMPLETE(hash 스킵, 로그 요약으로 확인).
5. 멱등 시나리오: 음식 설명 수정 → 새 UPSERT 행 → 배치 실행 → hash 불일치로 재임베딩·문서 교체. 삭제 → DELETE 행 → 배치 실행 → 문서 제거, 스캔 유사 음식 후보에서 제외 확인.

## 선행조건 (백필·배치 실행 전 필수)

- **배치 부팅 확인**: `kbap.vector.enabled=true` + `kbap.llm.embedding.enabled=true` 조합으로 batch 앱이 정상 부팅하는지 dev 에서 1회 확인 — `@ConditionalOnExpression` 조립과 임베딩 model/dimension 프로퍼티(기본값 없음, 누락 시 부팅 실패)는 DocumentDB 재현 불가로 자동 테스트가 못 덮는다.

- DocumentDB `kbap.foods` 에 `foodId` unique 인덱스 존재 확인: `db.foods.getIndexes()` — 없으면 `db.foods.createIndex({foodId: 1}, {unique: true})`. KB-318 구축분은 `embedding` 벡터 인덱스만 보장하므로 **백필 전 반드시 확인**(없으면 건당 풀스캔 + 중복 문서 위험 — DB 리뷰 Major#1).

## 기존 데이터 적재 운영 순서 (2026-08-13 개정 — 자동 백필 없음)

1. **인덱스 확인**(위 선행조건) — 수동 적재분 첫 배치 전 필수. 없으면 건당 풀스캔이 READY 전건 배수로 터진다.
2. **배포** — 배포만으로는 벡터 적재가 일어나지 않는다(자동 백필 폐기).
3. **랭체인 재수집** — 관리자 재수집으로 기존 음식 콘텐츠(긴 설명 포함) 최신화(1회 500건 상한, 나눠 실행).
4. **관리자 벡터 적재 지시** — `/admin/foods` 의 벡터 적재 액션으로 원하는 음식의 UPSERT 아웃박스 생성.
5. **`foodVectorSyncJob` 실행** — 이후 신규 건은 승인·수정·삭제 트리거가 증분 처리.

## 운영 참고

- 배치는 run-to-completion ECS 태스크 — 기존 `foodContentOutboxPublishJob` 과 동일한 방식으로 스케줄/수동 트리거.
- FAILED 잔존 건은 `/admin/foods` 대시보드 벡터 아웃박스 섹션에서 원인 확인 후 재처리 버튼으로 PENDING 복귀.
- DocumentDB 는 Testcontainers 재현 불가 — 어댑터 변경 시 dev 클러스터에서 3~4번 절차로 수동 검증(KB-319 선례).
