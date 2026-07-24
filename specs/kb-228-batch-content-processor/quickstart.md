# Quickstart: 배치 음식 콘텐츠 프로세서의 작업별 구현

**Date**: 2026-07-23 | **Plan**: [plan.md](plan.md)

## 검증 빌드·테스트

```bash
./gradlew :core:test :domain:food:test :infra:llm:test :app:batch:test   # 변경 모듈 테스트
./gradlew build                                                          # 전체(ArchUnit 포함)
```

통합 테스트는 MySQL Testcontainers 로 동작(Docker 필요). ArchUnit 제외: `-Dkotest.tags="!arch"`.

## 로컬 실행 (실제 LLM 호출)

```bash
# LLM 키·활성 플래그 — 미설정이어도 부팅은 성공(해당 작업은 음식별 실패 로그)
export KBAP_LLM_OPENAI_ENABLED=true KBAP_LLM_OPENAI_API_KEY=...     # ①②(단일 모델) + ③
export KBAP_LLM_UPSTAGE_ENABLED=true KBAP_LLM_UPSTAGE_API_KEY=...   # ③ fan-out
export KBAP_LLM_GEMINI_ENABLED=true KBAP_LLM_GEMINI_API_KEY=...     # ③ fan-out

SPRING_PROFILES_ACTIVE=local ./gradlew :app:batch:bootRun
```

## 수동 검증 시나리오 (스펙 SC 대응)

1. **SC-001**: 관리자 일괄 적재(KB-186) 로 INCOMPLETE 음식 1건 생성 → 배치 실행 → `food` row 에서 `name_translations`·`description(+translations)`·`avoidance_substances`·`spiciness(≥0)` 채워짐 확인. 이미지 없으므로 `content_status = INCOMPLETE` 유지(FR-008).
2. **SC-002**: ③ 만 실패하도록(예: upstage·gemini 비활성 → 유효 응답 1개) 실행 → ①② 결과는 커밋 유지, spiciness 는 -1 유지 → 키 복구 후 재실행 시 ③ 만 재시도.
3. **SC-004**: 실패 음식의 skip 로그(`음식 콘텐츠 처리 실패 — 건너뜀 foodId=...`)가 남고 잡은 COMPLETED 로 끝남.

## 관련 작업

- 선행(머지됨): KB-182(배치 골격·계약) · KB-224(LLM 클라이언트 4종) · KB-209(기피성분 스텝·센티널) · KB-186(관리자 INCOMPLETE 적재)
- 병행 주의: KB-223(PENDING_REVIEW 전이 — writer·contentStatus 를 만짐, 본 스펙은 processor·클라이언트 계약을 만짐. 충돌 지점: `transitionToReadyIfComplete` 주변)
- 범위 제외: 이미지 생성(`FoodImageGenerationClient` — 조립 주석·스텁 유지)
