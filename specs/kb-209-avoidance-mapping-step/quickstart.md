# Quickstart: 기피성분 매핑 스텝 (KB-209)

## 단위/통합 테스트

```bash
./gradlew :domain:food:test          # Food 센티널·assessAvoidance 단위
./gradlew :app:batch:test            # FoodAvoidanceMapProcessorTest(페이크 client)·프로세서·부팅
./gradlew :app:api:test              # AdminControllerTest 센티널 assert(활성화됨)·마이그레이션
./gradlew build                      # food 컬럼 변경 3곳 동기화 전수 검증 (필수)
```

## 로컬 실제 실행 (LLM 키 필요 — 루트 .env)

> **배포 순서 전제**: api 배포(Flyway `food_unassessed_sentinel` 적용) 전에는 신규 배치를 실행하지 않는다 — 구 스키마(CHECK 0~10·NOT NULL)에서 센티널 쓰기가 거부된다.

```bash
# INCOMPLETE 음식 준비: admin 일괄 적재 API 또는 스캔으로 신규 음식 생성 후
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun --args='\
  --spring.batch.job.enabled=true \
  --kbap.llm.openai.enabled=true --kbap.llm.upstage.enabled=true --kbap.llm.gemini.enabled=true'
```

## 확인 포인트

```sql
-- 미조사(백필 직후): spiciness=-1, avoidance_substances IS NULL
SELECT id, korean_name, spiciness, avoidance_substances, content_status
FROM food WHERE content_status = 'INCOMPLETE';

-- 조사완료: spiciness 0~10, JSON 배열([] = 무성분) — 4작업 완성 시 READY
SELECT id, korean_name, spiciness, JSON_LENGTH(avoidance_substances), content_status
FROM food WHERE avoidance_substances IS NOT NULL;
```

- 로그: 미지 코드 폐기 `warn`(code·foodId·modelId), 합의 미성립 `warn`, 비용 로그(`com.kbap.infra.llm.provider`).
- 재실행 멱등: 같은 잡 재실행 시 조사완료 음식은 LLM 호출 없음(skip-if-done), 실패 음식만 재시도.
