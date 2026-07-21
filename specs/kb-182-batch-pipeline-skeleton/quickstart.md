# Quickstart: 배치 콘텐츠 파이프라인 골격 (KB-182)

## 빌드·테스트

```bash
./gradlew build                          # 전체 (레거시 제거 후에도 그린이어야 함)
./gradlew :domain:food:test              # 전이 규칙·창구 테스트
./gradlew :app:batch:test                # 러너 골격·게이팅 테스트
```

## 배치 실행 (골격 확인)

```bash
# 기본은 러너 off — 부팅만 하고 종료
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun

# 콘텐츠 잡 실행 (스텝 본문 0개 상태 — INCOMPLETE 순회·Step 실행·이력 기록만 확인)
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun \
  --args='--spring.batch.job.enabled=true --kbap.batch.content.chunk-size=20'
```

잡 실행 on/off(`spring.batch.job.enabled`)·리더 페이지 크기(`kbap.batch.content.chunk-size`)는 재배포 없이 실행 인자/환경변수로 덮어쓴다. `RunIdIncrementer` 로 실행마다 새 인스턴스가 되어 야간 반복 실행이 가능하다. 실행 이력·스킵 카운트는 `BATCH_*` 테이블에 남는다.

## 골격 검증 시나리오 (테스트가 자동화하는 것)

1. **전이 규칙**(도메인 단위 테스트): 4작업 필드 조합별로 `transitionToReadyIfComplete` 호출 — 전부 채워진 조합만 READY, 이미 READY 는 멱등.
2. **창구**(Testcontainers): `getIncompleteFoods` 키셋 조회(INCOMPLETE·오름차순·afterId 이후·READY 제외) + `completeContent` 저장·전이.
3. **실패 격리**: Step 이 faultTolerant skip — 특정 음식 처리 중 예외는 그 건만 건너뛰고(INCOMPLETE 잔류) 잡 계속(배치 단위 테스트는 후속 보강).
4. **자동 실행 게이팅**: `spring.batch.job.enabled` 미설정/false 면 부팅 시 잡 미실행(부팅 테스트가 이 상태를 검증).

## 후속 태스크 연결 지점

- KB-183(이름 번역·설명)·KB-184(사진)·KB-209(기피성분·맵기): `FoodContentItemProcessor` 의 작업별 메서드(`generateImage`·`generateDescription`·`translateNames`·`mapAvoidance`) 본문에 LLM 호출을 채운다. `mapAvoidance` 는 KB-209 에서 API 3개 호출·종합 후 매핑 존재 여부를 반환.
- KB-186(신규 음식 적재): `Food.incomplete(koreanName)` 로 INCOMPLETE 행을 만드는 별도 잡 — 이 골격의 입력을 공급.
