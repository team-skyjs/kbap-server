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
| [0006](./0006-central-persistence-adapter-and-decoupled-batch.md) | 중앙 영속 어댑터 모듈(`:meogo-api:persistence`) 채택 + `meogo-batch` 완전 디커플드 | Accepted | 2026-06-28 | specs/001 · ADR-0001(supersede 일부) · ADR-0003 · ADR-0004 |
| [0007](./0007-git-branch-strategy.md) | Git 브랜치 전략 — develop+main 채택, github-flow→git-flow 점진 확장 | Accepted | 2026-06-28 | specs/001 · ADR-0001 · git-branch-strategy |
