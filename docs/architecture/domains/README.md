# Domains — Bounded Context 개요

Meogo 백엔드의 도메인 경계를 정의한다. 구현자는 이 문서를 기준으로 어떤 도메인 클래스에 어떤 필드·로직을 둘지 판단한다. 코드 구조(패키지·클래스명·파일명)는 구현 단계에서 조정할 수 있으나, **도메인 책임과 경계는 유지한다.**

> 모듈 의존·BC 경계의 **강제 규칙**은 [`../meogo-conventions.md`](../meogo-conventions.md), 모듈 구조의 상세 배경은 [`../meogo-api-module-structure.md`](../meogo-api-module-structure.md), 처리 파이프라인은 [`../meogo-data-ai-pipeline.md`](../meogo-data-ai-pipeline.md) 참고.
> 도메인 문서에서 쓰는 용어와 코드 표준명은 [`ubiquitous-language.md`](./ubiquitous-language.md)에 모은다.
> 메뉴·재료·알러지·다국어 의사결정 설명은 [`menu-ingredient-allergy-language-report.md`](./menu-ingredient-allergy-language-report.md)에 정리한다.

## 서비스 핵심 제약

- Meogo는 식당 탐색이 아니라 **음식 단위의 안전 판별·이해** 서비스다.
- MVP 핵심 입력은 메뉴판 이미지가 아니라 **클라이언트가 추출한 메뉴명 목록**이다.
- 위험도 판정 조건은 **알러지 / 종교 / 비건** 3가지 (매운맛·관심음식은 UX 보조, 판정 핵심 아님).
- 위험도는 `SAFE` / `CAUTION` / `DANGER` / `UNKNOWN` 4단계 고정.
- 음식 데이터는 한국어 원문 + 9개 언어로 사전 번역 저장(ADR-0003). 정적 UI 문구는 BC가 아니라 `meogo-core` 또는 별도 supporting resource로 관리.
- 캐시 미스 메뉴는 그 스캔에서 결과 없음. 재료 조사·9개국어 번역은 `meogo-batch`가 하루 1회 처리(ADR-0003).

## Active Bounded Context (5개)

| Context | 책임 | 문서 |
|---------|------|------|
| `food` | **검수된 음식 카탈로그** — 음식·재료·알러지/식이 매핑·9개국어 번역·데이터 상태 | [food.md](./food.md) |
| `member` | 사용자가 누구이며 어떤 식이 제한·선호를 갖는지 관리 (인증/인가는 내부 하위 영역) | [member.md](./member.md) |
| `scan` | 보낸 메뉴명이 어떤 음식으로 매핑됐고(또는 결과 없음) 당시 어떤 결과를 받았는지 기록 + 이력·횟수 제한 | [scan.md](./scan.md) |
| `assessment` | 특정 사용자에게 특정 음식이 안전한지 판정 (정책 도메인) | [assessment.md](./assessment.md) |
| `research` | **미스 메뉴 조사·종합 파이프라인** — 조사 대기열 + 3개 LLM 병렬·종합 → 음식 데이터 후보 (배치 전용) | [research.md](./research.md) |

> `review`는 제품 기획엔 남기되 **현재 도메인 설계·초기 구현 범위에서는 제외**한다. 추후 재개 시 `food`에 섞지 않고 별도 컨텍스트로 다시 설계한다 → [review.md](./review.md) (보류 메모).
>
> 보조/후보 컨텍스트: 인증/인가 = `member` 내부 하위 영역(책임 분리). 랭킹 소유권 = `member`. 재료 지식베이스가 커지면 `ingredient`/`food-knowledge` 분리 검토.

## 경계 원칙

- 각 컨텍스트는 **자기 언어**를 가진다. `scan`은 재료를 판단하지 않고, `assessment`는 메뉴판 위치를 다루지 않는다.
- **도메인 간 조합은 Application 계층에서** 한다. 메뉴판 스캔 분석은 `scan`·`food`·`member`·`assessment`를 쓰고, 미스 메뉴 조사(배치)는 `research`·`food`를 쓰지만, 이들이 서로의 내부 구현을 직접 알면 안 된다.
- JPA Entity / Mongo Document / Spring Data Repository / 영속 Adapter는 각 도메인 모듈(`:meogo-api:{food,member,scan,assessment,research}`) 내부에 숨긴다. 외부 모듈은 도메인 클래스·도메인 repository interface·도메인 결과 객체만 사용한다.
- **`assessment`는 `food`/`member`의 엔티티·영속 모델에 직접 의존하지 않는다.** Application이 음식 재료·사용자 식이 제한을 `assessment` 전용 입력 VO로 변환해 넘긴다.

## ID 참조 / 스냅샷 원칙

- 다른 Aggregate·Context의 객체 전체를 직접 들지 않는다. ID·코드·스냅샷 값을 사용한다 (예: `FoodIngredient`는 `ingredientId` 참조, `MenuScan`은 `foodId` + 결과 스냅샷).
- 시간이 지나면 원본이 바뀔 수 있는 값(스캔 당시 위험도·매핑 음식명·종합 재료 정보)은 **스냅샷**으로 보존한다. 최신 판정은 필요 시 다시 계산한다.

## MVP 제외 범위

식당 상세 · 지도 탐색 · 식당별 리뷰 · 음식별 리뷰/평점 도메인 구현 · 커뮤니티/댓글/좋아요 · 서버 OCR · 가공식품 분석 · 음식 사진 인식 · 실시간 매장 재료 검증.

## 새 도메인 문서를 쓸 때

기존 5개 문서(food/member/scan/assessment/research)를 패턴으로 따른다: **역할 → 포함/제외 기능 → 핵심 개념(필드·로직 판단 기준) → 상태 → 다른 컨텍스트와의 관계 → 구현 시 주의사항.**
