# Quickstart: LLM 스코어링 호출 비용 절감 (KB-93)

**Plan**: [plan.md](plan.md) | **Contract**: [contracts/compressed-scoring-llm-contract.md](contracts/compressed-scoring-llm-contract.md)

## 단위/골든 테스트 (실 네트워크 불요 — 개발 사이클 기본)

```bash
# 압축 프롬프트 골든 + 인덱스 파서 + selector (순수, Spring-free)
./gradlew :core:research:test

# fan-out 모델별 요청 분기 + 옵션 프로퍼티 바인딩/배선
./gradlew :infra:llm:test

# 배치 조율 — 페이크 fan-out(Gemini 만 번역, 설명 전무, 확정 게이트)
./gradlew :app:batch:test --tests "com.meogo.app.batch.scoring.*"

# 전체
./gradlew build
```

## 비용 실측 (SC-001 — 수동 스모크, 실 API 키 필요)

1. 루트 `.env` 에 `OPENAI_API_KEY` / `UPSTAGE_API_KEY` / `GOOGLE_API_KEY` 설정.
2. 로컬 도커 스택 기동(MySQL — `SPRING_PROFILES_ACTIVE=local`).
3. 실행:

```bash
./gradlew :app:batch:bootRun --args='\
  --spring.profiles.active=local \
  --meogo.scoring.runner.enabled=true \
  --meogo.llm.openai.enabled=true \
  --meogo.llm.upstage.enabled=true \
  --meogo.llm.gemini.enabled=true'
```

4. 로그에서 청크당·모델당 비용 확인 — 각 모델의 `costKrw` 가 **1.00 미만**이어야 한다(SC-001):

```
LLM 토큰 사용량 model=OPENAI promptTokens=... completionTokens=... costUsd=... costKrw=0.xx
```

동시 확인: OpenAI/Upstage 응답에 `t`(이름 번역) 없음(SC-003), 어느 응답에도 설명 텍스트 없음(SC-002 — 본문 확인은 `--logging.level.com.meogo.infra.llm.provider=DEBUG`), 모델별 호출 수 1회/청크(SC-004).

## 검증 요약 (수용 기준 → 검증 수단)

| SC | 수단 |
|----|------|
| SC-001 (₩1 미만) | 스모크 실행 + `costKrw` 로그 |
| SC-002 (설명 0) | 프롬프트 골든 테스트(지시문 부재) + DEBUG 본문 확인 |
| SC-003 (번역 단일 모델) | 프롬프트 골든(변형별 지시문) + 배치 페이크 테스트 |
| SC-004 (호출 1회/모델) | fan-out 단위 테스트(호출 카운트) |
| SC-005 (판단 동일) | 골든 동등성 테스트(R6) |
| SC-006 (테스트 통과) | `./gradlew build` |
| SC-007 (후속 티켓) | Jira KB 티켓 등록·spec 참조(US4) |
