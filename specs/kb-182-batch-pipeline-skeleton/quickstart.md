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

# 콘텐츠 잡 실행 (스텝 0개 상태 — INCOMPLETE 조회·루프·로그만 확인)
SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun \
  --args='--kbap.batch.content.runner.enabled=true --kbap.batch.content.chunk-size=20'
```

청크 크기·러너 on/off 는 재배포 없이 실행 인자/환경변수로 덮어쓴다(기존 운영 방식 동일).

## 골격 검증 시나리오 (테스트가 자동화하는 것)

1. **전이 규칙**: 4작업 필드 조합별로 `transitionToReadyIfComplete` 호출 — 전부 채워진 조합만 READY, 이미 READY 는 멱등.
2. **실패 격리**: 특정 음식 처리 중 예외 → 그 건만 INCOMPLETE 잔류, 나머지 정상 전이.
3. **청크 소진**: INCOMPLETE N건, 청크 크기 k → ⌈N/k⌉회 조회로 전량 방문, 0건이면 즉시 정상 종료.
4. **게이팅**: `runner.enabled` 미설정/false 면 러너 빈 미생성.

## 후속 태스크 연결 지점

- KB-183(이름 번역·설명)·KB-184(사진)·KB-209(기피성분·맵기): `FoodContentJob` 의 작업별 메서드(`generateImage`·`generateDescription`·`translateNames`·`mapAvoidance`) 본문에 LLM 호출을 채운다.
- KB-186(신규 음식 적재): `Food.incomplete(koreanName)` 로 INCOMPLETE 행을 만드는 별도 잡 — 이 골격의 입력을 공급.
