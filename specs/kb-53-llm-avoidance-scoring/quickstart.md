# Quickstart: 기피성분 포함 신뢰도 LLM 스코어링

**Feature**: kb-53-llm-avoidance-scoring | `:app:batch` 스코어링 잡 실행·검증 가이드.

## 무엇이 도는가
조사 대상 음식 → 10개 청크 → 3개 LLM **동시** 호출 → 각 응답에서 "포함된 성분만" + **음식명 9개 언어 번역 + 음식 설명(ko+9개 번역, ≤200자)** 파싱 → **3개 모두 성공 시에만** Consensus Ensemble 종합(성분) + 우선순위 단일 채택(텍스트) → (음식,성분)별 `inclusionConfidence`(1~100) + 음식별 `nameTranslations`·`description` 산출. 일부 실패 → 실패 모델 로깅 + 청크 미확정. **저장 안 함**(KB-54), **위험도 판정 안 함**(KB-9).

## 사전 조건
- `food`·`food_avoidance_substance`·avoidance 카탈로그 시드는 이미 존재(마이그레이션). 실데이터로 구동 가능.
- LLM 키/활성 플래그(KB-49): 루트 `.env` + `meogo.llm.{openai,upstage,gemini}.enabled/api-key`. 키 없으면 caller 빈 미생성 → **부팅은 안전**하나 스코어링은 성공 모델 0 → 전부 FAILED. 최소 1개 모델 활성 필요.

## 실행
```bash
# 배치 앱 실행(스코어링 잡). 프로필은 SPRING_PROFILES_ACTIVE 로.
./gradlew :app:batch:bootRun
```

## 활성 모델 확인(부팅 안전)
- 키 0개로도 컨텍스트 로딩 성공(빈 미생성). 스코어링 잡은 성공 모델이 0 이면 각 음식 `FAILED` 로그.
- 실키 3모델 스모크는 `@Disabled` 테스트 + 수동 절차(KB-49 계승) — 실제 응답 형식/키 점검용.

## 테스트(헌법 I — 실 네트워크 없이)
```bash
./gradlew :core:research:test          # 프롬프트·파서·앙상블 순수 단위(골든 74 포함)
./gradlew :infra:persistence:test      # FoodScoringSource 어댑터(MySQL Testcontainers)
./gradlew :app:batch:test              # 잡 종단(페이크 LlmFanoutClient+페이크 FoodScoringSource)
```

## 수용 확인 체크(스펙 SC 매핑)
- [ ] 대기열이 항상 10개 청크로 분할(마지막 잔여 허용, 빈 대기열 무호출) — SC-001.
- [ ] 산출 `inclusionConfidence` 전부 정수 1~100(범위 이탈 0, 0→1 clamp) — SC-002.
- [ ] 문서 §5 골든(`비빔밥-계란`, score[2,1,2]·prob[90,70,80]) → **74** 재현 — SC-003.
- [ ] **3개 모델 모두 취합 시에만 확정**(1~2개 성공은 미확정+로깅, 부분 집계 0건) — SC-004.
- [ ] 산출값을 `RiskLevel.fromInclusionProbability` 에 넣어 거부 0건(별도 확인 테스트) — SC-005.
- [ ] 청크당 벽시계 ≈ 가장 느린 단일 모델(병렬) — SC-006.
- [ ] score 일치 수준별 agreement 1.0/0.9/0.75 반영 — SC-007.
- [ ] 음식별 `nameTranslations` 가 대상 9개 언어 부분집합(`ko` 키 없음)이라 `food.name_translations` 로 변환 없이 저장 가능 — SC-008.
- [ ] 음식 `description`(ko + 9개 번역) 각 값 공백 포함 ≤230자(목표 200), `LocalizedText` 동형 — SC-009.
- [ ] 일부 모델 실패 시 실패 모델별 로그(modelId + 사유), 부분 결과 유입 0 — SC-010.

## 다음 단계
- **KB-54(T5)**: `FoodScoringResult` 를 `food_avoidance_substance` 매핑에 영속 + KB-9 위험도 연동.
- 전용 조사 대기열 테이블(재조사·중복제거·재시도), `:application:batch` 승격(트리거 도달 시).
