# Implementation Plan: k6 스캔 부하 테스트

**Branch**: `kb-393-k6-scan-load-test` | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md)

## Summary

dev 환경의 메뉴판 스캔 전체 여정(5단계 체인)을 k6 로 동시 5→50→145 로 실행해 스캔 p95·실패율과 동시 스캔 중 타 API 영향을 실측한다. 스캔은 건당 외부 비용(≈10원)이 있어 총 스캔량을 200건으로 고정한다. 이미지는 실제 업로드 체인으로 **1회 시드**해 objectKey를 확보하고, 부하 루프는 **티켓+스캔**만 반복하며 그 키를 재사용한다(반복 가능 구조). 인증은 소셜 로그인을 우회해 dev JWT_SECRET 으로 access token 을 직접 서명한다. **애플리케이션(kotlin) 코드는 건드리지 않는다** — 산출물은 `k6/` 스크립트와 실행·관찰·기록 절차뿐이다.

## Technical Context

**Language/Version**: k6(JavaScript, ES modules), Python 3(표준 라이브러리만 — 토큰 서명)
**Primary Dependencies**: k6(grafana/k6, 로컬 `brew install k6`), 앱 변경 없음
**Storage**: 대상 앱의 dev MySQL/S3 — 테스트가 쓰기(회원 1건 UPDATE/INSERT, 스캔 이미지 업로드). 스키마 변경 없음
**Testing**: 부하 테스트 자체가 산출물 — 유닛 테스트 대상 아님(아래 Constitution Check 참조)
**Target Platform**: dev.kbap.site (prod 금지)
**Project Type**: 운영/검증 스크립트(리포지토리 `k6/` 디렉터리)
**Performance Goals**: 측정이 목적 — 사전 목표 SLO 없음. 동시 50/145 두 지점의 곡선을 얻는다
**Constraints**: 스캔 총량 ≤200건, 외부 비용 ≤≈2,000원, dev 전용
**Scale/Scope**: 최대 동시 145 VU, 5단계 체인, 회차 3개(5/50/145)

## Constitution Check

- **I. Test-First (NON-NEGOTIABLE)**: 본 작업은 프로덕션 코드 변경이 없다 — k6 스크립트 자체가 검증 도구이고, 대상 앱 로직은 기존 그대로다. 따라서 "구현 전 실패 테스트" 대상이 없다(작성할 애플리케이션 코드가 없음). 사용자도 모니터링/인프라 계열 작업에 대해 테스트 코드 비작성을 지시했다. **위반 아님 — 적용 대상 없음.**
- **II. Bounded Contexts / III. Dependency Direction / IV. Persistence Ownership**: kotlin 모듈·패키지·엔티티를 만들지 않으므로 해당 없음. `k6/` 는 앱 소스 트리 밖이다.
- **V. (문서/규약)**: 결과는 지식 위키에, 스크립트는 `k6/` 에 남긴다(spec FR-009). Kotlin 주석 금지 규약은 대상 파일 없음.

**게이트 통과** — 헌법 위반 없음, Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```
specs/kb-393-k6-scan-load-test/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1 (회차·계정·픽스처 개념)
├── quickstart.md        # Phase 1 (준비→실행→관찰→정리 런북)
├── contracts/
│   └── scan-chain.md    # 5단계 HTTP 체인 계약(경로·헤더·바디)
└── checklists/
    └── requirements.md
```

### Source Code (repository root)

```
k6/
├── seed-image.js        # [시드 1회] presign→PUT→complete → objectKey 출력   ← 신규
├── scan-burst.js        # [루프] 티켓+스캔, per-vu-iterations, SCAN_IMAGE_PATH 재사용  ← 수정
├── mint-token.py        # HS256 access token 서명(JWT_SECRET)              ← 작성됨
└── menu-board.jpg       # 시드 원본 이미지(사용자 준비, .gitignore)
```

**Structure Decision**: 최상위 `k6/` 디렉터리 하나. 스크립트를 **시드(1회)와 부하 루프(반복)로 분리**해 이미지 업로드 I/O를 측정 루프에서 뺀다 — 스캔(LLM) 엔드포인트가 측정 대상. 앱 소스는 무변경. 원본 이미지는 바이너리라 `.gitignore`, objectKey는 시드 스크립트가 출력해 `-e SCAN_IMAGE_PATH`로 주입(커밋 안 함).

## Phase 0 — research.md 로 해소할 항목

1. k6 실행기 선택(closed vs open model, per-vu-iterations 로 정확히 N건) — 결정 기록
2. 소셜 로그인 우회(무상태 JWT 검증 이용) 의 정당성·범위(dev 한정)
3. 비용 안전장치(앞 단계 실패 시 스캔 스킵, 계단 실행) 설계
4. 관찰 지표 매핑(k6 메트릭 ↔ Grafana 대시보드)·429 분리 집계
5. k6 결과를 홈 Prometheus 로 remote_write 할지(옵션)

## Phase 1 — 설계 산출물

- **data-model.md**: 회차(run)·테스트 계정·이미지 픽스처의 필드와 상태(더미 계정 생성→사용→비활성화).
- **contracts/scan-chain.md**: 5단계 각 요청/응답 계약(경로·필수 헤더·바디·성공 판정), 코드 기준.
- **quickstart.md**: 준비(계정·토큰·이미지) → 리허설(VUS=5) → 본 실행(50/145) → 관찰 → 기록/정리 런북.
