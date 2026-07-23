# Quickstart: 음식 이미지 비동기 생성

**Date**: 2026-07-24 | **Plan**: [plan.md](./plan.md)

## 로컬 실행

```bash
# 1. 인프라 기동 (MySQL·Redis)
docker-compose up -d

# 2. api 기동 (Flyway가 image_batch·image_batch_item·shedlock·content_status 마이그레이션 적용)
OPENAI_API_KEY=sk-... ./gradlew :app:api:bootRun

# 3. 이미지 일괄 제출 (즉시 응답)
curl -X POST http://localhost:8080/api/v1/admin/foods/images
# → {"payload":{"submittedBatchCount":N,"submittedFoodCount":M}}

# 4. 회수는 1시간 틱 — 로컬 검증은 스케줄러 빈 메서드 직접 호출 또는 틱 주기 짧게 조정
```

## 검증 시나리오 (Kotest, 페이크 기반)

| 검증 | 방법 |
|---|---|
| 제출 JSONL 조립 | 페이크 `FoodImageBatchClient`가 받은 entries의 custom_id=food PK·10건 분할 확인 |
| 중복 제출 가드 | 제출 2회 연속 호출 → 두 번째는 후보 0건 |
| 회수 파싱·저장 | 페이크가 completed + 결과 스트림 반환 → 페이크 `StorageObjectStore`에 put, imageRef 갱신, item DONE, 배치 COLLECTED |
| 수렴 전이 | 텍스트완료+이미지없음→TEXT_READY / 텍스트완료+이미지있음→PENDING_REVIEW / 텍스트미완+이미지도착→INCOMPLETE 유지 |
| 실패·만료 | 페이크가 failed/expired → item FAILED, 다음 제출 후보에 재포함 |
| 멱등 재회수 | DONE 항목 섞인 배치 재회수 → PENDING만 처리 |
| ShedLock | 통합 테스트(Testcontainers)에서 shedlock 테이블 선점 동작 |

```bash
./gradlew build   # 전체 검증 (테스트 손스텁 CREATE TABLE 동기화 누락도 여기서 잡힘)
```

## 운영 배포 체크리스트

- `OPENAI_API_KEY` 기존 env 재사용 — 신규 시크릿 없음
- api 2대 롤링 배포 안전: 틱을 놓치면 다음 틱이 회수, 회수 도중 사망은 lockAtMostFor(30m) 리스 만료 후 재선점, PENDING 항목만 재처리
- 제출 후 24시간 내 회수 완료 예상 — `image_batch.batch_status = 'SUBMITTED'`가 24h 이상 잔존하면 expired 경로 확인
