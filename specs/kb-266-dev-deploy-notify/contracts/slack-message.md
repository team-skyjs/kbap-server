# Contract: 슬랙 메시지 형식

Incoming Webhook JSON(`text` 단일 필드, mrkdwn). 상세 전문 미포함 — 요약 + 링크만.

## 성공 (dev)

```
[DEV] 2026-07-30 · a1b2c3d — 배포 완료 ✅

API 추가: 1 · 변경: 2 · 삭제: 0 · Breaking: 없음
• 추가 POST /api/v1/reviews
• 변경 GET /api/v1/foods/detail
• 변경 GET /api/v1/home

Release: https://github.com/<owner>/<repo>/releases/tag/dev-20260730-a1b2c3d
```

## 성공 (prod) — "배포 시작" 문구 고정

```
[PROD] 2026-07-30 · f4e5d6c — 블루/그린 배포 시작 🚀 (성패 판정은 ECS 소관)
...동일 구조...
```

## 변경 없음

`API 변경 없음` 한 줄로 명시(섹션 생략 금지).

## 초기 스냅샷

`초기 OpenAPI 스냅샷 — 비교 기준 없음(다음 배포부터 diff 제공)`

## 재배포 (build=false)

```
[DEV] 재배포 · a1b2c3d — 기존 이미지 재기동 ♻️ (코드 변경 없음, 릴리즈 미생성)
```

## 배포 실패

```
[DEV] 배포 실패 ❌ · a1b2c3d
실행: <워크플로 run 링크>
```

## 릴리즈 실패(배포는 성공)

```
[DEV] 배포 완료 ✅ · 릴리즈 노트 생성 실패 ⚠️ — 원인: actions summary 확인
실행: <워크플로 run 링크>
```

## 규칙

- 환경 표기 `[DEV]`/`[PROD]` 필수(혼동 금지).
- 엔드포인트 목록이 10건 초과면 상위 10건 + `외 N건 — 상세는 Release 첨부` 로 절단.
- 웹훅 URL·토큰을 메시지 본문에 포함하지 않는다.
