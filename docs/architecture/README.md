# Architecture (백엔드)

`kbap-api` 백엔드의 아키텍처 레퍼런스. 이 repo 전용이며 공유 대상이 아니다(제품 개요·기획 PRD는 공유 `agent-hub`에 있음).

> 강제 규칙(DDD 적용 방식, 모듈 구성, 의존 규칙)은 이 폴더의 [`kbap-conventions.md`](./kbap-conventions.md)에 따로 둔다. 여기(README)는 "어떻게 동작하는가"를 설명하고, conventions는 "무엇을 지켜야 하는가"를 규정한다.

## 문서 구성

- **이 문서 (`README.md`)** — 진입점 + 도메인 맵(경계 한눈에 보기).
- 서비스 전체 설명(제품 개요)은 **공유 `agent-hub/kbap-service-overview.md`** — 백엔드 repo 밖.
- **[`kbap-api-module-structure.md`](./kbap-api-module-structure.md)** — api/application/core/infra와 도메인별 subproject 구조·책임 상세 (설계 배경).
- **[`kbap-data-ai-pipeline.md`](./kbap-data-ai-pipeline.md)** — 메뉴판 스캔 처리 흐름, LLM 종합, DB·번역 정책 (백엔드 기준).
- **[`kbap-conventions.md`](./kbap-conventions.md)** — DDD 정의, 모듈 구성/레이어링, 도메인 간 의존 규칙 (규범).
- **[`domains/`](./domains/)** — Bounded Context별 특성. 1 BC = 1 파일. 개요는 [`domains/README.md`](./domains/README.md).
- **[`domains/ubiquitous-language.md`](./domains/ubiquitous-language.md)** — 도메인 용어와 코드 표준명 사전.

---

## 도메인 맵

> 상세 BC 정의는 [`domains/README.md`](./domains/README.md), 의존 방향 강제 규칙은 [`kbap-conventions.md`](./kbap-conventions.md). **Active Bounded Context는 5개:**

| Context | 책임 | 비고 |
|--------|------|------|
| [`food`](./domains/food.md) | 검수된 음식 카탈로그 — 음식·재료·알러지/식이 매핑·9개국어 번역 | 한국어 원문 + 9개 언어 사전 번역 저장(ADR-0003). `research` 종합 결과를 영속 |
| [`member`](./domains/member.md) | 회원·국적·언어·식이 제한 프로필·선호·랭킹 | 인증/인가는 내부 하위 영역(책임 분리). 위험도 개인화 기준 |
| [`scan`](./domains/scan.md) | 메뉴명 → 음식 매핑(또는 결과 없음)·응답 스냅샷·이력·횟수 제한 | 서버 OCR·LLM 안 함(클라가 메뉴명 추출) |
| [`avoidance`](./domains/avoidance.md) | 사용자 조건 × 음식 재료 → 위험도(SAFE/CAUTION/DANGER/UNKNOWN) 판정 | 서비스 핵심 정책 도메인 |
| [`research`](./domains/research.md) | 미스 메뉴 조사·종합 파이프라인 — 대기열 + 3개 LLM 병렬·종합 | 배치 전용(ADR-0004). [`kbap-data-ai-pipeline.md`](./kbap-data-ai-pipeline.md) |

> **`review` 는 보류** — 제품 기획엔 남기되 현재 도메인 설계·초기 구현 범위 제외. 재개 시 `food`에 섞지 않고 별도 BC로 재설계 ([domains/review.md](./domains/review.md)).
> 향후 후보: 재료 지식베이스가 커지면 `ingredient`/`food-knowledge` 분리.

<!-- BC가 추가/변경되면 위 표, domains/README.md, 해당 domains/*.md 를 함께 갱신한다. -->
