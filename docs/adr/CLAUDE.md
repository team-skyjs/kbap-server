# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

이 폴더(`docs/adr/`)에서 ADR을 만들거나 수정할 때 **반드시** 따른다. 컨벤션·인덱스는 [`README.md`](./README.md), 양식은 [`_template.md`](./_template.md).

## ADR 규칙

- **ADR = 의사결정 한 건.** 기능 결과 보고서가 아니라, 아키텍처적으로 의미 있는 결정 1개의 Context·Decision·Alternatives·Consequences를 기록한다.
- **불변(append-only).** 이미 Accepted된 ADR의 결정 내용을 고치지 않는다. 결정이 바뀌면 **새 ADR을 써서 이전 것을 supersede**하고, 옛 ADR 상태를 `Superseded by ADR-NNNN`으로 바꾼다(상태 줄만 갱신은 허용).
- **번호**: `NNNN-kebab-title.md`, `0001`부터 순차. **번호 재사용 금지**(폐기돼도 비워둠).
- **상태**: `Proposed` → `Accepted` → (`Superseded by ADR-NNNN` | `Deprecated`).
- 새 ADR은 [`_template.md`](./_template.md)를 복사해 작성하고, 작성 후 [`README.md`](./README.md) 인덱스에 한 줄 추가한다.
- 어느 SpecKit 사이클(`specs/NNN-slug`)에서 나온 결정인지 **관련** 항목에 링크한다.
- 강제 규칙([`docs/architecture/meogo-conventions.md`](../architecture/meogo-conventions.md))을 바꾸는 결정이면, ADR을 남기고 conventions도 함께 갱신한다.
