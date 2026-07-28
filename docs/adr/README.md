# Architecture Decision Records (ADR)

이 백엔드(`meogo-api`)의 아키텍처적으로 의미 있는 **의사결정 기록**. SpecKit 사이클을 돌며 중요한 결정이 생기면 한 장씩 남긴다. 작성 규칙은 [`CLAUDE.md`](./CLAUDE.md).

## 컨벤션 (요약)

- 파일명 `NNNN-kebab-title.md` (0001부터, 번호 재사용 금지).
- ADR은 **불변** — 결정이 바뀌면 새 ADR로 supersede.
- 새 ADR: [`_template.md`](./_template.md) 복사 → 작성 → 아래 인덱스에 추가.
- 상태: `Proposed` → `Accepted` → (`Superseded` | `Deprecated`).

## 인덱스

| # | 제목 | 상태 | 날짜 | 관련 |
|---|------|------|------|------|
| [0001](./0001-multi-app-modular-layout.md) | 멀티앱 모듈 레이아웃 (meogo-api 컨테이너 + batch + common) | Accepted | 2026-06-26 | meogo-conventions · 헌법 v1.1.0 |
| [0002](./0002-buildsrc-convention-plugins.md) | 공통 빌드 설정 — buildSrc 컨벤션 플러그인 | Accepted | 2026-06-26 | ADR-0001 · gradle-made-easy |
| [0003](./0003-pretranslated-batch-menu-pipeline.md) | 메뉴 데이터 파이프라인 — 사전 번역 배치(9개국어) + 캐시 미스 결과 없음 | Accepted | 2026-06-27 | meogo-data-ai-pipeline · ADR-0001 |
| [0004](./0004-research-bounded-context.md) | research 바운디드 컨텍스트 신설 — 미스 메뉴 조사·종합, 배치 트리거 | Accepted | 2026-06-27 | ADR-0003 · ADR-0001 |
| [0005](./0005-unified-api-package-and-presentation-rename.md) | meogo-api 패키지 규약 통일(`com.meogo.api.<모듈명>`) + web `api`→`presentation` 리네임 | Accepted | 2026-06-27 | specs/001 · ADR-0001 |
| [0006](./0006-central-persistence-adapter-and-decoupled-batch.md) | 중앙 영속 어댑터 모듈(`:meogo-api:persistence`) 채택 + `meogo-batch` 완전 디커플드 | Superseded by 0008·0012 | 2026-06-28 | specs/001 · ADR-0001(supersede 일부) · ADR-0003 · ADR-0004 |
| [0007](./0007-git-branch-strategy.md) | Git 브랜치 전략 — develop+main 채택, github-flow→git-flow 점진 확장 | Accepted | 2026-06-28 | specs/001 · ADR-0001 · git-branch-strategy |
| [0008](./0008-modular-monolith-shared-domain.md) | 모듈러 모놀리스 — 공유 도메인/영속, batch 직접 의존 | Accepted | 2026-06-29 | specs/004-avoidance-catalog · ADR-0001(supersede 일부) · ADR-0006 |
| [0009](./0009-food-avoidance-direct-mapping.md) | 음식↔기피성분 직접 매핑 — 레시피/재료 모델 제거 | Accepted | 2026-07-04 | specs/kb-40 · Jira KB-40 · ADR-0008 |
| [0010](./0010-llm-adapter-module-named-infra-llm.md) | LLM 호출 어댑터 전용 모듈 `:infra:llm` 신설 — 배치가 직접 의존 | Accepted | 2026-07-06 | specs/kb-49 · Jira KB-49 · ADR-0008 |
| [0011](./0011-scoring-domain-in-research-batch-orchestration.md) | 기피성분 스코어링 도메인 로직은 `:core:research`, 조율은 `:batch` | Accepted | 2026-07-06 | specs/kb-53 · Jira KB-53 · ADR-0004 · ADR-0010 |
| [0012](./0012-dissolve-persistence-module-and-ports.md) | persistence 모듈 해체·리포지토리 port 폐기 — 영속은 도메인 모듈 안에 internal | Superseded in part by 0014 | 2026-07-13 | specs/kb-134 · Jira KB-134 · ADR-0006·0008(supersede) · 헌법 v3.0.0 |
| [0013](./0013-lang-english-fallback.md) | 표시 언어(lang) — 필수화 + 미지원 코드 영어 폴백 | Accepted | 2026-07-20 | specs/kb-201 · Jira KB-201 · specs/008(supersede) · 헌법 v4.0.0 |
| [0014](./0014-relax-persistence-encapsulation.md) | 영속 캡슐화 완화 — 엔티티·리포지토리 public, 소비 계층 직접 참조 | Accepted | 2026-07-22 | specs/kb-220 · Jira KB-220 · ADR-0012(supersede 일부) · 헌법 v5.0.0 |
| [0015](./0015-scan-lang-unification-and-profile-language-removal.md) | 스캔 표시 언어를 `lang` 파라미터로 통일 + 회원 프로필 언어 설정 제거 | Accepted | 2026-07-23 | specs/kb-229 · Jira KB-229 · ADR-0013(후속 해소) |
| [0016](./0016-module-diet-three-app-modules.md) | 모듈 다이어트 — 앱 모듈 api·batch·common 3개 통합, 경계 강제 ArchUnit 이관 | Superseded in part by 0017 | 2026-07-28 | specs/kb-244 · Jira KB-244 · ADR-0012(모듈 구성 supersede) · 헌법 v6.0.0 |
| [0017](./0017-api-feature-package-flattening.md) | API 모듈 기능 패키지 평탄화 | Accepted | 2026-07-28 | ADR-0016(패키지 결정 supersede) · 헌법 v7.0.0 |
